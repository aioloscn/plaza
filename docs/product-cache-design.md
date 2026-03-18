# 店铺商品缓存设计方案

## 1. 业务背景
为了支撑高并发下单和商品检索，不能直接对数据库进行频繁的查询与更新。特别是对于“提交订单页”的商品信息展示以及首页“店铺商品搜索”等高频接口，必须引入多级缓存机制（Redis + Elasticsearch）。

## 2. Redis 缓存设计 (主打：高频读取与实时库存扣减)
**定位：** 用于订单提交流程中的商品信息获取与高并发下的库存扣减。

### 2.1 缓存结构
- **商品基础信息**
  - **Key**: `product:info:{productId}`
  - **Type**: `String` (JSON格式)
  - **Value**: 包含 `id`, `shopId`, `name`, `price`, `imageUrl`, `status` 等。
  - **更新时机**: 商品后台修改后发送 MQ 更新 Redis；或者在订单服务未命中时回退查库并重建。
- **商品库存**
  - **Key**: `product:stock:{productId}`
  - **Type**: `String` (纯数字)
  - **更新时机**: 初始由商品服务同步；下单时通过 Redis 的原子操作 `DECR` 进行扣减，超时未支付时 `INCR` 归还。

### 2.2 下单防超卖机制 (分布式锁)
在扣减缓存库存时，直接对共享资源进行操作存在并发风险，我们采用 **Redisson 分布式锁** 保驾护航：
1. 锁定商品：`redissonClient.getLock("lock:stock:" + productId)`。
2. 检查缓存中剩余库存是否足够。
3. 执行扣减 `opsForValue().decrement(...)`。
4. 释放锁。
5. 异步发送 MQ，由库存服务缓慢持久化到数据库中，保护 MySQL。

## 3. Elasticsearch 缓存设计 (主打：多维度检索与店铺聚合)
**定位：** 参考 `plaza-home` 中对于店铺(`shop`)的 Canal 监听索引方案，将商品(`product`)同步到 ES，用于复杂的搜索、分类、排序。

### 3.1 同步机制 (Canal -> MQ -> ES)
- 监听 `product` 表的 `INSERT`, `UPDATE`, `DELETE` binlog 事件。
- 提取变更行数据。
- 按 `shop_id` 作为路由/父文档字段，或者在 `shop_products` 索引中建立关联。

### 3.2 ES Index 结构建议
**索引名**: `shop_products`
**Mapping**:
```json
{
  "properties": {
    "id": { "type": "long" },
    "shop_id": { "type": "long" },
    "name": { 
      "type": "text", 
      "analyzer": "ik_max_word",
      "fields": { "keyword": { "type": "keyword" } }
    },
    "price": { "type": "double" },
    "stock": { "type": "integer" },
    "status": { "type": "integer" },
    "create_time": { "type": "date" }
  }
}
```
**查询场景**：当用户进入店铺页时，直接在 ES 中根据 `shop_id` 进行 `term` 查询，并可通过 `price` 排序，响应速度极快，完全与数据库解耦。

## 4. 订单超时机制设计
- **订单创建**：生成状态为 `CREATED` 的订单，计算倒计时(创建时间+10分钟)，通过 VO 返回给前端用于倒计时渲染。
- **延迟队列**：下单同时发送一条 RocketMQ 延迟消息 (10分钟级别)。
- **订单取消**：
  1. 消费者收到消息，校验订单是否仍为 `CREATED`。
  2. 若是，则触发状态机事件 `OrderEvent.CANCEL` 将状态置为 `CLOSED`。
  3. 执行 `INCR` 归还 Redis 库存，并归还数据库库存。
