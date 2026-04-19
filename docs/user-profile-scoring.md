# 用户画像打分逻辑说明

本文说明 `plaza-home` 中用户画像如何构建，以及如何参与 ES 排序打分
当前实现已经支持 Apollo 规则调权、近期新店铺识别，以及数据库驱动的运营曝光干预

## 1. 整体链路

用户画像搜索链路分为两段：

1. 从已支付订单侧构建用户画像（店铺偏好、类目偏好、价格偏好、置信度）
2. 产出互斥的店铺维度（favorites、recent new、recent active）
3. 每天 20:00 由 XXL-Job 全量重建画像，写入快照表并回填 Redis
4. 用户支付成功后，由 `plaza-order` 直接更新 `plaza-home` 所在 Redis 库中的画像缓存
5. 把画像注入 ES `function_score`，和距离/质量/热度、运营干预一起混排

核心类：

- `UserProfileShopSearchServiceImpl`：画像构建与缓存
- `UserProfileEsQueryBuilderSupport`：ES 查询和打分函数构建
- `UserProfileCacheRefreshService`：支付成功后增量刷新 Redis 画像

## 2. 画像存储策略

- `user_shop_profile_snapshot`：夜间全量任务产出的画像快照表，标量字段单列存储，列表/映射字段使用 MySQL `json`
- `HOME_USER_PROFILE`：线上查询优先读取的 Redis 画像缓存
- 查询顺序：先读 Redis，未命中再回源快照表，并重新写回 Redis
- 更新策略：
  - 每天 20:00 全量重建并落库
  - 支付成功后只增量刷新 Redis，不回写快照表

这样做的目的：

- 快照表负责稳定回源和审计
- Redis 负责承接支付成功后的实时画像变化
- 两者结合后，查询链路既有实时性也有兜底能力

## 3. 画像构建逻辑

### 2.1 店铺偏好如何累计

在订单聚合阶段，按订单累加店铺行为强度：

- 行为强度 `behaviorWeight = recencyWeight * amountWeight`
- 对同一 `shopId` 累加：`shopStrength.merge(shopId, behaviorWeight, Double::sum)`

含义：

- 下单次数越多，累计值越高
- 越近期订单，时间衰减后权重越高
- 金额越高，金额权重越高（对数压缩避免极端值失真）

### 2.2 店铺维度去重规则

店铺画像拆成 3 个互斥维度：

- `favoriteShopIds`：30 天窗口内长期偏好最强的店铺
- `recentNewShopIds`：近 7 天新出现，且在近 7 天之前没有历史成交的店铺
- `recentActiveShopIds`：近 7 天活跃，但不在上述两个集合中的店铺

去重规则：

- 若同一 `shopId` 同时命中 favorites 和 recent，只保留 favorites
- 若同一 `shopId` 属于近 7 天新店，则不再重复落入 recent active
- 这样可以避免同一店铺在多个维度叠加导致权重过高

这个设计表达的业务语义是：

- favorites 代表稳定长期口味
- recent new 代表最近新尝试的新店
- recent active 代表近期活跃但不是长期偏好的变化口味

### 2.3 近期活跃与新店铺

在近期窗口（默认 7 天）内，会单独累计两类店铺：

- `recentShopStrength`：近 7 天活跃店铺强度
- `recentNewShopStrength`：近 7 天首次出现的新店铺强度

### 2.4 类目维度

类目画像基于已支付订单的 `OrderItem` 聚合：

- 对同一 `categoryId` 累加 `itemWeight`
- 生成 `categoryPreference` 强度映射
- 同时保留 `favoriteCategoryIds` 作为兜底列表

这样当类目强度 map 不可用时，仍能按 TopN 类目做粗粒度加权

### 2.5 归一化

店铺偏好分数会归一化到 `0~1`：

- 取 TopN 店铺（默认最多 6）
- 用当前最高分作为分母
- 每个店铺偏好强度 = `score / peak`

## 4. 画像如何影响 ES 排序

在 `profileFunctions(profile)` 中，把画像变成 `function_score.functions`：

- 若有 `shopPreference`，按店铺逐条加权：
  - `weight = shopPreferenceBase + strength * (shopPreferenceRange + profileConfidence * shopPreferenceConfidence)`
  - 并通过 `filter: term(id=shopId)` 精确命中店铺
- 若无细粒度偏好，退化用 `favoriteShopIds` 做兜底加权
- `recentNewShopIds` 会作为独立维度加权
- `recentActiveShopIds` 只对未命中 favorites 和 recent new 的店铺加权
- 类目优先使用 `categoryPreference` 强度 map，缺失时退化到 `favoriteCategoryIds`
- 运营曝光权重通过数据库表 `shop_search_boost_config` 注入

结论：

- 用户经常光顾的店，`strength` 更高，最终 `weight` 更高，排序会前移
- 用户最近尝试的新店，会得到单独的探索性曝光加权
- 运营指定的商家或店铺，即使不在用户画像内，也可以获得额外曝光

## 5. 画像置信度的作用

`profileConfidence` 是个总闸门，范围约 `0.1~1.0`，由以下因素共同决定：

- 订单覆盖度（样本量）
- 明细覆盖度（订单项样本）
- 近期行为占比（recentRatio）

置信度越高，画像相关加权越强；置信度低时会自动收敛，避免“弱画像误导排序”

## 6. 与其他分数的关系

最终不是“只看画像”，而是混合打分：

- 距离衰减（gauss）
- 店铺质量（`score`、`seller_score`）
- 热度脚本分
- 画像偏好分（店铺/新店/类目/价格）
- 运营干预分（商家级/店铺级曝光）

`function_score` 的 `score_mode=sum`、`boost_mode=sum`，表示上述信号按权重累加

## 7. 一个简化示例

假设用户最近 30 天在店铺 A/B 的行为强度分别是：

- A = 12
- B = 6

归一化后：

- A.strength = 1.0
- B.strength = 0.5

若 `profileConfidence = 0.8`，且 Apollo 中店铺偏好配置为默认值，则店铺权重约为：

- A.weight = `1.8 + 1.0 * (5 + 0.8*5) = 10.8`
- B.weight = `1.8 + 0.5 * (5 + 0.8*5) = 6.3`

因此在其它条件接近时，A 会明显排在 B 前面

## 8. Apollo 配置项

规则参数已统一收敛到 `home.user-profile.score.*`：

- `home.user-profile.score.distance.*`：距离衰减权重
- `home.user-profile.score.quality.*`：店铺评分、商家评分权重
- `home.user-profile.score.keyword.*`：关键词召回 boost
- `home.user-profile.score.distance.*`：距离衰减权重与衰减参数
- `home.user-profile.score.hot.*`：热度脚本权重与脚本内部系数
- `home.user-profile.score.shop.preference-*`：长期店铺偏好权重
- `home.user-profile.score.shop.favorite-*`：favorites 兜底权重
- `home.user-profile.score.shop.recent-*`：recent active 权重
- `home.user-profile.score.shop.recent-new-*`：近 7 天新店铺权重
- `home.user-profile.score.category.preference-*`：类目偏好权重
- `home.user-profile.score.category.favorite-*`：类目兜底权重
- `home.user-profile.score.price.*`：价格偏好权重
- `home.user-profile.score.profile-confidence-floor`：画像置信度最低裁剪值
- `home.user-profile.score.preference-strength-floor`：偏好强度最低裁剪值

Apollo 生效方式：

- `plaza-home` 启动后通过 Apollo Client 拉取配置
- 代码每次构建 ES 权重时都实时读取 Apollo 当前值
- Apollo 发布后，无需重启服务即可应用到后续请求

业务曝光权重不再走 Apollo，而是走数据库表 `shop_search_boost_config`：

- `seller_id`：商家级连锁投放
- `shop_id`：店铺级单点投放
- `boost_weight`：业务曝光权重
- `start_time/end_time`：投放生效窗口
- `status`：启停控制

## 9. 调参建议

- 想强化“常去店优先”：
  - 提高店铺偏好函数的基础权重或上限
  - 缩短时间衰减半径，强化近期行为
- 想扶持新店探索：
  - 提高 `recent-new` 相关权重
- 想做运营曝光：
  - 通过 `shop_search_boost_config` 维护商家级或店铺级曝光权重
- 想减少个性化过拟合：
  - 降低 `profileConfidence` 参与系数
  - 提高最低样本要求，低样本用户走弱画像
- 想提升稳定性：
  - 保持画像缓存定时刷新
  - 关注画像命中率、TopN 点击率、排序漂移

## 10. 排障检查清单

- 用户画像缓存是否存在（`HOME_USER_PROFILE`）
- Redis 未命中时快照表 `user_shop_profile_snapshot` 是否存在对应用户数据
- XXL-Job 中 `homeUserShopProfileBuildJob` 的 cron 是否配置为 `0 0 20 * * ?`
- 支付成功后 `plaza-order` 是否成功写入 `shopRedisTemplate` 对应的 database 2
- `shopPreference` 是否非空且有值
- `favoriteShopIds`、`recentNewShopIds`、`recentActiveShopIds` 是否按互斥规则生成
- `favoriteCategoryIds` 或 `categoryPreference` 是否有值
- ES 查询 JSON 是否带了 `profileFunctions` 生成的 `functions`
- 命中的店铺是否在 `filter term(id=...)` 中
- Apollo 是否下发了预期规则参数
- `shop_search_boost_config` 是否存在有效的商家级/店铺级曝光记录
- 是否被距离/类目过滤条件提前排除
