# Plaza 电子商务微服务系统

## 📖 项目简介

Plaza 是一个基于 Spring Boot 和 Spring Cloud 构建的大型分布式电商微服务系统。它涵盖了电商业务的核心链路，包括商品浏览、购物车、订单生成、秒杀系统、支付网关对接等。配合独立的网关服务 `octopus` 和前端项目 `plaza-web`，构建了一个完整的高并发、高可用的电商解决方案。

## 🛠️ 核心技术栈

- **核心框架**: Spring Boot 2.x / Spring Cloud (Nacos 作为注册与配置中心)
- **数据库**: MySQL 8.0
- **ORM 框架**: MyBatis-Plus
- **分库分表**: Sharding-JDBC (海量订单与流水数据拆分)
- **缓存**: Redis (Lettuce 客户端，多级缓存架构)
- **消息队列**: RocketMQ (基于 Spring Cloud Stream 构建可靠消息传递)
- **搜索引擎**: Elasticsearch (高亮搜索、分词与聚合分析)
- **数据同步**: Canal (监听 MySQL binlog 实现 DB 与 ES 的异构数据实时同步)
- **任务调度**: XXL-Job (分布式定时任务处理)
- **支付网关**: 支付宝沙箱环境对接 (Alipay Sandbox)
- **设计模式**: 责任链模式 (订单处理链路)、状态机模式 (订单状态安全流转)

## 📦 模块说明

项目采用 Maven 多模块结构进行领域划分：

- `plaza-common`: 公共模块 (包含工具类、全局异常处理、公共配置等)
- `plaza-enums`: 枚举与异常定义模块 (全局错误码、Redis Key 枚举、订单状态等)
- `plaza-model`: 数据模型模块 (统一的 PO、DTO、VO 定义)
- `plaza-service`: 业务逻辑与数据访问层 (各领域 Service 实现与 MyBatis Mapper)
- `plaza-home`: 首页与门户模块 (基于 ES 的商品搜索、Canal 数据同步、分类与店铺数据展示)
- `plaza-shop`: 店铺与商品管理模块 (商品详情查询、商品缓存预热)
- `plaza-cart`: 购物车模块 (基于 Redis Hash 缓存、MQ 异步持久化)
- `plaza-order`: 订单与秒杀核心模块 (状态机流转、责任链下单、Redis Lua 脚本扣减库存、RocketMQ 异步削峰、支付宝支付)
- `plaza-mq`: 消息队列模型模块 (统一维护 MQ Topic、Group 常量和消息体实体)

## ✨ 系统亮点与架构设计

### 1. 高并发秒杀系统设计

- **限流前置**: 配合 `octopus` 网关，在网关层基于 **Bucket4j** (底层结合 Redis) 实现分布式令牌桶限流，阻挡瞬时无效流量。
- **防超卖机制**: 利用 Redis Lua 脚本保证“校验购买记录 + 扣减库存”的原子性，避免高并发环境下的超卖问题。
- **异步削峰**: Redis 扣减库存成功即视为抢购成功，随后发送 RocketMQ 消息异步创建订单并落库，极大提高接口吞吐量。
- **超时回滚**: 基于 RocketMQ 延迟消息 (Level 14) 实现未支付订单的超时自动取消与库存回滚，防止恶意占坑。

### 2. 订单状态机与责任链

- 订单生成与校验过程采用 **责任链模式 (Chain of Responsibility)**，将黑名单校验、库存预占、促销优惠计算等环节解耦，便于灵活编排与后续业务扩展。
- 订单状态流转采用 **状态机模式 (State Machine)**，严格控制订单在“待支付”、“已支付”、“已发货”、“已取消”等状态间的安全、单向转换。

### 3. 异构数据同步与搜索

- 使用 **Canal** 伪装成 MySQL Slave 监听 binlog 变更，将商品数据的 CUD 操作实时同步至 **Elasticsearch**，实现高性能的复杂商品搜索与分词查询，实现读写分离架构。

### 4. 多级缓存与防击穿

- 采用本地缓存 (Caffeine) + 分布式缓存 (Redis) 的多级缓存架构。
- 采用布隆过滤器 (BloomFilter) 结合空值缓存策略，有效防止恶意请求导致的缓存穿透问题；核心业务引入逻辑过期时间应对缓存击穿。

## 🚀 环境与部署准备

### 1. 基础设施要求

- **MySQL 8.x**: 需开启 binlog (用于 Canal 同步)
- **Redis 6.x+**
- **RocketMQ 4.x/5.x**
- **Elasticsearch 7.x+**
- **Nacos Server**
- **XXL-Job Admin Server**
- **Canal Server**

### 2. 配置修改

请检查并修改各业务模块 (如 `plaza-order`, `plaza-home`) 下 `src/main/resources/application-dev.yml` 中的中间件连接地址。系统已预留了相关环境变量注入点：

- `NACOS_HOST`, `MYSQL_HOST`, `REDIS_HOST`, `ROCKETMQ_HOST` 等。

### 3. 关联项目

完整的 Plaza 平台运行需要依赖以下兄弟项目：

- **网关服务**: `octopus` (负责统一路由、鉴权、全局限流)
- **认证服务**: `badger` (负责用户登录认证与 Token 鉴权分发)
- **前端 Web**: `plaza-web` (基于 React/Vue 的前台页面)
