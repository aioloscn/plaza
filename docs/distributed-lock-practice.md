# Plaza 项目分布式锁实战开发文档

## 1. 文档目标

使用当前 `plaza` 项目完成一次可复现、可验收的分布式锁练习，重点解决：

- 多实例下定时任务重复消费
- 同一业务实体（shop）并发更新冲突
- 锁续约、超时与异常释放

本次练习以 `plaza-home` 模块的 Canal 同步链路为主线。

## 2. 项目现状与练习切入点

### 2.1 技术与运行基础

- 工程是 Maven 多模块 Spring Boot 项目（`plaza-home` 为启动模块）
- 已启用定时任务能力（`@EnableScheduling`）
- 已配置 Redis（可直接作为分布式锁存储）
- 当前仓库未发现现成分布式锁实现代码

### 2.2 适合加锁的主流程

Canal 定时任务每 100ms 拉取一次变更并写入 ES，是最典型的并发冲突入口：

1. `run()` 拉取消息并处理 `INSERT/UPDATE/DELETE`
2. `indexES()` 按 `shop/category/seller` 关联查询 shop 数据
3. `bulkIndexToES()` 批量写入 ES

多节点部署时，这条链路会面临重复消费与同对象并发写。

## 3. 练习目标（分三阶段）

## 阶段 A：任务级互斥锁（先做）

目标：同一时刻只允许一个实例执行 `CanalScheduling.run()` 的主处理逻辑。

- 锁 Key：`lock:canal:run`
- 锁 TTL：15s（示例值）
- 获取失败：直接返回，不执行本轮处理
- 获取成功：进入处理逻辑，最终必须释放

验收标准：

- 启动两个实例后，任一时刻仅一个实例打印“获取锁成功”的日志
- 无重复 ack 同一批次的异常行为

## 阶段 B：实体级细粒度锁（进阶）

目标：针对同一个 `shopId` 的并发变更进行串行化，避免重复写 ES 或交叉覆盖。

- 锁 Key：`lock:shop:index:{shopId}`
- 作用范围：`indexES()` 内部按 shop 维度处理时
- 建议策略：失败快速返回或有限重试（如 2 次，间隔 50ms）

验收标准：

- 并发触发同 `shopId` 变更时，ES 写操作按串行发生
- 失败路径不阻塞其他 `shopId` 的处理

## 阶段 C：锁健壮性（完善）

目标：增强锁的稳定性与可观测性。

- 自动续约：业务执行超过 TTL 时续租
- 安全释放：仅锁持有者可释放（value 比对 + Lua）
- 指标埋点：成功率、等待时长、超时次数、异常释放次数

验收标准：

- 人为制造慢处理（sleep）后，锁不会中途过期导致并发进入
- 任意异常路径都不会遗留死锁（TTL 到期可恢复）

## 4. 实现方案建议

## 方案 1：原生 Redis（推荐用于学习原理）

实现要点：

1. 加锁：`SET key value NX PX ttl`
2. 释放：Lua 脚本比较 `value` 后删除
3. value：`uuid + threadId`
4. 可选续约：后台定时任务仅为持有中的锁续期

优点：清楚理解分布式锁核心机制。  
风险：需要自行处理续约、重入、可重试等细节。

## 方案 2：Redisson（推荐用于工程落地）

实现要点：

1. 新增 Redisson 依赖与配置
2. 通过 `RLock.tryLock(waitTime, leaseTime, unit)` 获取
3. 在 `finally` 里判断持有后再释放

优点：内置看门狗续约、重入能力、API 完整。  
风险：对框架行为理解不足时，容易“会用但不知边界”。

建议路径：先用方案 1 完成阶段 A/B，再切换方案 2 对比效果。

## 5. 代码改造位置建议

### 5.1 新增包结构（建议）

`plaza-home/src/main/java/com/aiolos/plaza/home/lock`

- `DistributedLockService`：统一锁接口
- `RedisDistributedLockService`：原生 Redis 实现
- `LockConstants`：集中维护 lock key 前缀与默认 TTL

### 5.2 变更点

1. 在 `CanalScheduling.run()` 外层加任务级互斥锁
2. 在 `indexES()` 处理单 shop 写入前加实体级锁
3. 所有锁释放统一 `finally` 处理
4. 日志中打印 lockKey、ownerId、waitMs、result

## 6. 验证与压测脚本思路

## 6.1 本地双实例验证（必做）

- 启动 Redis、MySQL、Canal、ES
- 启动两个 `plaza-home` 实例（不同端口）
- 观察同一时刻仅一个实例进入任务级处理

## 6.2 并发冲突验证（必做）

- 制造同一 `shopId` 高频 UPDATE（可用 SQL 循环）
- 检查日志中同 key 是否串行进入
- 对比 ES 最终文档一致性

## 6.3 异常恢复验证（必做）

- 在持锁逻辑中注入异常
- 确认锁最终可释放或 TTL 到期后自动恢复

## 7. 常见问题与规避

- 锁 TTL 过短：业务未完成锁已过期，导致并发进入
- 锁 TTL 过长：异常后恢复慢，吞吐下降
- 未做 owner 校验直接 `DEL`：会误删他人锁
- 锁粒度过大：系统整体吞吐下降
- 重试无退避：高并发下放大 Redis 压力

## 8. 练习完成标准（Checklist）

- 已实现任务级锁，双实例无重复并行消费
- 已实现实体级锁，同 shop 并发写被串行化
- 已实现安全释放（owner 校验）
- 已覆盖异常路径与恢复验证
- 已记录关键监控日志并可定位锁问题

## 9. 本项目内参考代码位置

- 定时任务与 Canal 消费：`plaza-home/.../canal/CanalScheduling.java`
- Canal 连接创建：`plaza-home/.../canal/CanalClient.java`
- 启动与调度开启：`plaza-home/.../HomeApplication.java`
- Redis 配置：`plaza-home/src/main/resources/application-dev.yml`

