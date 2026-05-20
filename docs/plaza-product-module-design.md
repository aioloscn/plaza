# plaza-product 模块设计文档

## 1. 设计结论

本次商品域改造，确定采用：

- **方案 B**：统一商品中心，交易主干择优复用
- **方案 2**：在当前 `plaza` 仓库内新增 `plaza-product` module，不立即拆成独立微服务

最终目标不是单纯新增几张电商表，而是把当前简单的 `product` 能力升级为可同时支撑：

- 外卖/即时零售商品
- 电商商品

同时避免把 `plaza-shop` 继续堆成店铺、商品、价格、计重、物流的大杂烩模块

---

## 2. 为什么要新建 `plaza-product`

当前 `plaza-shop` 更适合承担的是：

- 店铺侧应用服务
- 商品发布入口
- 店铺商品列表展示
- 秒杀活动管理

但统一商品中心需要额外承接：

- SPU / SKU 主模型
- 商品销售属性
- 商品多场景扩展能力
- 阶梯价规则
- 计重规则
- 商品渠道配置
- 商品快照读取
- 面向交易侧的商品查询 Facade

如果继续堆在 `plaza-shop`，会有几个明显问题：

- 店铺域和商品域耦合越来越深
- 后续外卖和电商的差异逻辑会在同一模块里横向扩散
- `plaza-order`、`plaza-cart` 想调用商品能力时，不得不依赖一个职责过重的模块
- 后续如果要把商品中心独立部署，迁移成本更高

因此，推荐将商品域独立为 `plaza-product`，但仍然保留在当前多模块单仓库结构中

---

## 3. 模块定位

`plaza-product` 的定位是：

- **统一商品中心模块**
- **负责定义“商品是什么”**
- **不负责定义“订单如何成交”**

一句话理解：

- `plaza-product` 管商品主数据和商品能力
- `plaza-order` 管确认单、下单、支付、退款
- `plaza-shop` 管店铺经营视角的应用入口

---

## 4. 模块职责边界

## 4.1 `plaza-product` 负责什么

- 商品 SPU 管理
- 商品 SKU 管理
- 商品销售属性管理
- 商品图片和基础详情
- 商品上下架状态
- 商品渠道能力配置
- 阶梯价规则定义
- 计重规则定义
- 商品物流能力元数据
- 商品快照聚合查询
- 面向购物车、确认单、订单的商品 Facade

## 4.2 `plaza-product` 不负责什么

- 购物车写入和合并
- 订单创建
- 支付和退款
- 运费试算总编排
- 库存冻结状态机
- 发货履约编排

这些仍然建议留在：

- `plaza-cart`
- `plaza-order`

## 4.3 与其它模块的关系

### `plaza-shop`

关系：

- `plaza-shop` 作为店铺经营侧入口，调用 `plaza-product` 提供的商品应用服务
- 店铺和商品的关系由店铺侧发布流程驱动，但商品主数据由 `plaza-product` 承载

建议边界：

- `plaza-shop` 保留 Controller 和店铺经营编排
- `plaza-product` 承担真正的商品领域逻辑

### `plaza-cart`

关系：

- `plaza-cart` 不再直接依赖简单 `product` 表
- 购物车应通过 `plaza-product` 查询 SKU 快照、基础价格、上下架状态、计重元数据

### `plaza-order`

关系：

- `plaza-order` 通过 `plaza-product` 获取确认单所需商品快照
- `plaza-order` 负责把价格、计重、物流结果快照化并落单

### `plaza-home`

关系：

- `plaza-home` 仍然优先负责门店搜索与推荐
- 如果后续要做电商商品搜索，可以由 `plaza-product` 输出检索数据，再由专门搜索模块消费

---

## 5. 推荐的 Maven 模块结构

根 `pom.xml` 新增：

```xml
<module>plaza-product</module>
```

推荐定位：

- 打包方式先与现有业务模块保持一致
- 先作为仓库内领域模块存在
- 后续如果商品域持续膨胀，再评估是否独立部署

不建议当前阶段直接做成独立微服务，原因：

- 当前改造重点是领域边界重构，不是服务治理
- 现在就拆服务，会把注册发现、鉴权、调用链、容灾复杂度提前引入
- 仓库内先拆模块，更利于快速迭代和代码迁移

---

## 6. 推荐的包结构

建议 `plaza-product` 采用分层包结构：

```text
com.aiolos.plaza.product
├─ controller
├─ service
│  ├─ app
│  ├─ facade
│  └─ impl
├─ domain
│  ├─ product
│  ├─ sku
│  ├─ price
│  ├─ weight
│  ├─ channel
│  └─ snapshot
├─ manager
├─ convert
├─ model
│  ├─ bo
│  ├─ dto
│  └─ vo
└─ config
```

各层建议职责如下：

- `controller`：后台商品管理接口、商品查询接口
- `service.app`：应用服务，编排多个领域能力
- `service.facade`：提供给 `plaza-cart`、`plaza-order` 的统一商品查询入口
- `domain`：领域核心逻辑，如 SKU 聚合、价格规则、计重规则
- `manager`：封装对 Mapper、缓存、外部网关的组合调用
- `convert`：对象转换
- `model`：请求、响应、DTO

---

## 7. 推荐的数据模型

## 7.1 核心主表

### `product_spu`

建议字段：

- `id`
- `shop_id`
- `biz_type`
- `spu_name`
- `spu_code`
- `category_id`
- `brand_id`
- `main_image`
- `album_images`
- `sale_mode`
- `status`
- `description`
- `create_time`
- `update_time`

字段说明：

- `biz_type`：区分外卖、电商等业务线
- `sale_mode`：区分普通售卖、按重量售卖、套餐售卖等

### `product_sku`

建议字段：

- `id`
- `spu_id`
- `shop_id`
- `biz_type`
- `sku_code`
- `sku_name`
- `sale_attr_json`
- `market_price`
- `sale_price`
- `status`
- `weight_value`
- `weight_unit`
- `volume_value`
- `image_url`
- `create_time`
- `update_time`

## 7.2 能力扩展表

### `product_ladder_price`

- `id`
- `sku_id`
- `biz_type`
- `min_quantity`
- `max_quantity`
- `ladder_price`
- `status`

### `product_weight_rule`

- `id`
- `sku_id`
- `biz_type`
- `pricing_weight_type`
- `weight_precision`
- `min_weight`
- `max_weight`
- `step_weight`
- `rounding_mode`
- `ext_config_json`

### `product_channel_rel`

- `id`
- `spu_id`
- `sku_id`
- `biz_type`
- `channel_code`
- `channel_status`
- `ext_config_json`

## 7.3 场景扩展表

### `product_local_ext`

用于承接外卖/即时零售特有能力，例如：

- 营业时段内可售限制
- 门店即时配送限制
- 加料/套餐扩展
- 展示标签

### `product_ecommerce_ext`

用于承接电商特有能力，例如：

- 默认物流模板
- 发货地配置
- 售后规则
- 多渠道配送限制

设计原则：

- 公共商品主数据收敛到核心表
- 场景差异落扩展表
- 不把所有业务差异都塞进主表字段

---

## 8. 推荐的服务接口

`plaza-product` 至少应对外提供两类 Facade

## 8.1 面向购物车的 Facade

例如：

- `queryCartSkuSnapshot`
- `batchQueryCartSkuSnapshot`

返回内容建议包含：

- `spuId`
- `skuId`
- `shopId`
- `bizType`
- `skuName`
- `imageUrl`
- `salePrice`
- `status`
- `saleAttr`
- `weightMeta`

## 8.2 面向确认单/下单的 Facade

例如：

- `queryOrderSkuSnapshot`
- `batchQueryOrderSkuSnapshot`
- `querySkuPriceRules`
- `querySkuWeightRules`
- `querySkuChannelRules`

返回内容建议比购物车快照更完整，便于确认单试算和落单快照

---

## 9. 依赖方向建议

推荐依赖方向：

```text
plaza-shop  -> plaza-product
plaza-cart  -> plaza-product
plaza-order -> plaza-product
```

不建议：

```text
plaza-product -> plaza-order
plaza-product -> plaza-cart
```

原因：

- 商品中心应当保持被依赖方角色
- 避免交易逻辑反向侵入商品域
- 保证未来独立部署时依赖关系清晰

---

## 10. 与现有代码的迁移关系

当前与商品直接相关的代码主要散落在：

- `plaza-shop`
- `plaza-service`
- `plaza-model`
- `plaza-order`
- `plaza-cart`

推荐迁移策略不是一次性大搬家，而是分阶段完成

## 10.1 第一阶段：新模块落地，不立刻删除旧逻辑

做法：

- 新建 `plaza-product`
- 新增商品领域对象和服务接口
- 先保留旧 `ProductService` 和 `ProductMapper`
- 在 `plaza-product` 内封装统一商品 Facade

目标：

- 让新链路先能依赖 `plaza-product`
- 老链路继续运行，避免一次性大改

## 10.2 第二阶段：把商品查询逐步收口到新 Facade

优先改造：

- `plaza-cart`
- `plaza-order`

思路：

- 先改读取链路
- 再改后台商品维护链路
- 最后再考虑是否收缩旧 `ProductService`

## 10.3 第三阶段：按业务线补扩展能力

外卖优先补：

- 套餐/加料
- 门店可售控制

电商优先补：

- 阶梯价
- 计重
- 物流模板

---

## 11. 第一阶段建议交付清单

如果按最小可落地路径推进，建议第一阶段先做下面这些

### 11.1 代码层

- 新建 `plaza-product` module
- 新建商品应用启动类或基础配置
- 新建商品 Facade
- 新建商品快照 DTO
- 新建价格规则 DTO
- 新建计重规则 DTO

### 11.2 数据层

- `product_spu`
- `product_sku`
- `product_ladder_price`
- `product_weight_rule`
- `product_channel_rel`
- `product_ecommerce_ext`

### 11.3 接口层

- 后台 SPU/SKU 管理接口
- 面向购物车的 SKU 查询接口
- 面向下单的商品快照接口

---

## 12. 不建议的做法

- 不建议继续在当前简单 `product` 表上无限追加字段
- 不建议把店铺域和商品域继续混在 `plaza-shop`
- 不建议现在就把 `plaza-product` 拆成独立微服务
- 不建议把购物车、订单、运费试算、履约都塞进商品模块

---

## 13. 最终建议

最终建议如下：

- 新建 `plaza-product` module
- 将其定位为统一商品中心
- 统一外卖与电商的商品主数据模型
- 交易和履约主干继续留在 `plaza-order`
- 店铺经营入口继续留在 `plaza-shop`
- 先模块内重构，后续再决定是否独立服务化

一句话总结：

**方案 B + 方案 2 的最佳落地方式，就是在当前仓库内新增 `plaza-product`，把“商品是什么”这件事先彻底收口**

---

## 14. 下一步建议

建议紧接着补两份文档：

1. `plaza-product-table-design.sql`
2. `plaza-product-migration-plan.md`

前者用于直接落表，后者用于规划从现有 `product` 迁移到统一商品中心的步骤
