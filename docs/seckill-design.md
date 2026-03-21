# Plaza 秒杀系统与令牌桶限流设计文档

## 1. 系统概述
针对高并发秒杀场景，结合 Plaza 现有微服务架构（Spring Cloud + Redis + RocketMQ），设计了一套高可用、防超卖的秒杀系统。核心思想是**限流前置、缓存预热、异步削峰**。
在此基础上引入**令牌桶（Token Bucket）算法**，由于已经引入了 `octopus-gateway` 项目，我们将令牌桶限流前置到**网关层 (Gateway)**进行拦截，在流量进入具体业务微服务前就把瞬时激增流量过滤掉，最大程度保护底层数据库与核心服务。

## 2. 核心架构设计
秒杀链路将遵循以下原则和流转步骤：
1. **活动预热**：提前将秒杀商品信息、库存、令牌桶配置预热到 Redis，避免直接穿透至数据库。
2. **网关层令牌桶限流**：秒杀请求到达 `octopus-gateway` 后，利用网关的全局过滤器结合 Redis Lua 脚本首先获取令牌。获取失败立即返回 HTTP 429 或定制的“活动火爆，请重试”响应。
3. **前置拦截与防刷**：使用 Caffeine (L1) + Redis (L2) 拦截对商品状态、活动时间的频繁查询；结合 Redis 控制单一用户的请求频率（防刷）。
4. **原子扣减（Lua防超卖）**：在 Redis 中利用 Lua 脚本保证“校验购买记录+扣减库存”的原子性，性能远高于分布式锁。
5. **异步下单（MQ削峰）**：扣减成功即代表“抢购成功”，随后通过 RocketMQ 发送消息，异步落库生成订单。前端通过轮询接口查询最终下单结果。

## 3. 令牌桶限流算法设计
**算法原理**：
- 系统按照预设的固定速率（如 500 个/秒）向桶中放入令牌。
- 桶的容量有上限（如 1000 个），当桶满时，新生成的令牌会被丢弃。
- 用户请求必须从桶中取走 1 个令牌才可进入秒杀核心链路，取不到则直接被拒绝（限流）。

**Plaza 实现方案（Gateway + Redis + Lua）**：
- 由于系统引入了 `octopus-gateway`，全局限流的最佳实践是将其放在**网关层**。网关作为所有微服务请求的统一入口，能最有效地阻挡无效流量，避免它们消耗下游微服务（如 Web 容器线程池）的资源。
- 方案选择：在 `octopus-gateway` 项目中，基于 Spring Cloud Gateway 的 `RequestRateLimiter` 结合 **Redis + Lua 脚本**实现分布式令牌桶。
- Redis 中记录两个关键属性：`last_time`（上次补充令牌的时间）和 `current_tokens`（当前桶中剩余令牌数）。
- 每次请求经过网关时，网关通过 Lua 脚本根据当前时间与 `last_time` 的差值计算出期间应补充的令牌数，更新 `current_tokens`，然后尝试扣减。扣减成功则路由到 `plaza-order` 或秒杀服务，失败则直接在网关层返回 HTTP 429。

## 4. 数据库结构设计

```sql
-- 秒杀活动表
CREATE TABLE `seckill_activity` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `shop_id` bigint(20) NOT NULL COMMENT '店铺ID',
  `product_id` bigint(20) NOT NULL COMMENT '商品ID',
  `price` decimal(10,2) NOT NULL COMMENT '秒杀价',
  `stock` int(11) NOT NULL COMMENT '秒杀总库存',
  `start_time` datetime NOT NULL COMMENT '开始时间',
  `end_time` datetime NOT NULL COMMENT '结束时间',
  `status` tinyint(4) DEFAULT '0' COMMENT '状态 0:未开始 1:进行中 2:已结束',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_shop_id` (`shop_id`),
  KEY `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动表';
```

## 5. 详细业务流程
### 5.1 运营预热阶段
- 运营在后台上架活动，系统将库存数据同步至 `Redis`（`seckill:stock:{activityId}`）。
- 开启本地消息表预热，确保数据强一致。

### 5.2 抢购阶段
1. **网关限流拦截**：秒杀请求到达 `octopus-gateway`，网关利用 Spring Cloud Gateway RateLimiter (Redis + Lua) 获取令牌。获取成功则放行至业务微服务，失败则直接返回“活动火爆”。
2. **频控校验**：到达业务服务后，判断用户是否重复请求（`seckill:limit:{userId}`）。
3. **Redis 扣减库存**：调用 Lua 脚本：
   - 判断库存是否 `> 0`。
   - 判断该用户是否已抢购过（检查集合 `seckill:bought_users:{activityId}`）。
   - 若通过，则 `stock - 1`，将 `userId` 加入已抢购集合。
4. **异步发送 MQ**：
   - 构造 `SeckillOrderMessage`，通过 `StreamBridge` 发送至 RocketMQ。
   - 直接给前端响应“抢购排队中”。

### 5.3 异步落库与订单生成阶段
1. 消费者 `SeckillOrderConsumer` 监听到消息。
2. 开启事务：创建父子订单（由于含有 `shop_id`，复用现有的 `parent_order` 和 `orders` 跨店结算表架构设计），扣减 DB 真实库存（乐观锁 `update ... where stock > 0`）。
3. 发送延迟消息（Level 14，15分钟）至延迟队列用于未支付超时取消。

### 5.4 超时取消与回滚
- 若15分钟内未支付，消费者处理取消逻辑：
  1. 更新订单状态为“已取消”。
  2. 恢复 DB 真实库存。
  3. **必须回滚 Redis 库存**：`INCR` 对应活动库存，并将用户从已抢购集合中 `SREM` 移除，确保能再次参与抢购（防止幽灵复活Bug）。

## 6. 技术规范落地建议
依据 Plaza 架构规范：
1. **Redis Key 管理**：在 `plaza-enums` 的 `RedisKeyEnum`（或新增 `SeckillRedisKeyEnum`）中统一定义上述所有前缀，避免硬编码。
2. **MQ 规范**：消息实体放在 `plaza-mq` 下的 `Message` 类中；消费者通过 `@Bean` 函数式注册，并配置在 `application.yml` 的 `function.definition`。
3. **缓存防穿透**：对于秒杀活动详情查询，若查不到数据，设置短时间的空值缓存，不建议强行引入布隆过滤器以防过期或下架问题。
