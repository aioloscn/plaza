# plaza 电商店铺与电商商品改造方案

## 1. 背景

当前 `plaza` 的核心数据模型更偏向本地生活/到店零售场景：

- `shop` 关注经纬度、营业时间、人均价、附近推荐
- `product` 只有单价、简单库存、图片、上下架状态
- `home` 搜索和推荐默认围绕附近门店展开
- `cart` 默认按 `shopId + productId` 组织购物车商品
- `order` 虽然已经具备父子单、支付、退款、库存预占等能力，但商品侧仍然假设是简单 SKU 或无 SKU 商品

本次要新增的是电商店铺与电商商品，且后续会接入：

- 计重
- 阶梯价
- 物流渠道
- 外部项目接口调用

这意味着新能力不仅是“多几个字段”，而是商品建模、价格计算、库存锁定、履约发货、运费试算都会发生变化

---

## 2. 先回答核心问题

### 2.1 复用原来的 `shop` 和 `product` 表，还是新建一套

结论：**不建议直接复用现有 `shop/product` 作为电商主模型，推荐新建一套电商商品域模型**

更准确地说，推荐采用下面这套混合策略：

- **店铺域**：新建电商店铺模型，不直接复用现有 `shop`
- **商品域**：新建电商 SPU/SKU、价格、计重、物流能力等模型，不直接复用现有 `product`
- **交易主干**：尽量复用现有 `orders / parent_order / payment / refund / mq_local_message` 能力
- **订单扩展**：为电商订单增加扩展表，而不是把所有复杂字段硬塞进当前 `orders / order_item`

这不是“全部重写一套”，而是**电商商品域和履约域新建，支付主链路与订单状态主链路复用**

### 2.2 如果新建的话，后续流程是不是几乎都要重写一套

结论：**不需要全部重写，但也绝不是只改两张表**

可以分成两类看：

**可以复用的部分**

- 父子订单模型
- 支付单聚合模型
- 支付回调处理
- 本地消息表与重试机制
- 退款、补偿、状态机主框架
- 基础账户、地址、商家主体等通用能力

**需要新建或重构的部分**

- 电商店铺建模
- 电商商品 SPU/SKU 建模
- 阶梯价计算
- 计重规则与计费口径
- 物流渠道选择与运费试算
- 电商购物车
- 电商确认单与价格快照
- 电商订单明细扩展
- 电商发货/履约编排

所以正确判断是：**不会重写支付中台，但需要新建电商商品域，并改造交易编排入口**

---

## 3. 为什么不建议直接复用现有表

### 3.1 `shop` 表明显偏本地生活门店

当前 `shop` 的核心字段包含：

- `longitude`
- `latitude`
- `opening_time`
- `closing_time`
- `per_capita_price`

这套模型天然服务于：

- 附近门店推荐
- 到店/即时零售展示
- 营业时间过滤
- 人均消费排序

但电商店铺更关注：

- 店铺类型
- 发货地
- 店铺资质
- 默认物流模板
- 履约 SLA
- 售后规则

如果强行复用，后续会出现大量问题：

- 一个表同时服务“附近门店”和“电商店铺”，语义会混乱
- `home` 搜索、推荐、ES 索引都要额外加业务线判断
- 许多字段对电商店铺无意义，数据质量会越来越差

### 3.2 `product` 表承载不了电商复杂商品

当前 `product` 只有：

- `shop_id`
- `name`
- `price`
- `stock`
- `description`
- `image_url`
- `status`

这更像简单单规格商品，不适合直接承载：

- SPU/SKU 多规格
- 销售属性
- 阶梯价
- 计重规则
- 包装重量/净重
- 物流模板
- 多渠道配送限制
- 运费试算快照

如果直接在 `product` 上不断加字段，最终会出现：

- 表结构膨胀
- 大量字段仅部分业务线可用
- 代码里充满 `if (businessType == ...)`
- 购物车、库存、下单、查询都要写大量分支

### 3.3 复用旧表不等于改动更小

很多时候“复用老表”看起来省事，实际上总代价更高。原因是：

- 你要在所有老链路里打业务线分支
- 你要保证旧门店能力不能被电商逻辑污染
- 你要给缓存、搜索、购物车、下单、库存、发货都增加兼容判断

最终不是少写代码，而是把所有模块都改脏

---

## 4. 两种方案对比

### 4.1 方案 A：直接复用 `shop/product`，在原模型上扩字段

### 设计思路

- 给 `shop` 增加 `biz_type`
- 给 `product` 增加 `product_type`
- 再补若干扩展表承载阶梯价、计重、物流模板
- 下单时按业务类型做分支计算

### 优点

- 早期表面上看接入快
- 原来的管理后台、基础查询接口似乎可以少改一点
- ID 体系天然统一

### 缺点

- `shop` 语义被污染，附近门店与电商店铺混在一起
- `product` 会变成超级大宽表或半残废主表
- `cart/home/order/stock/search` 都要加业务类型分支
- 新老逻辑相互牵制，回归测试范围会越来越大
- 后续再接促销、物流拆单、包邮规则时会继续恶化

### 适用前提

只有在下面条件同时成立时才勉强可以考虑：

- 电商商品仍然是单规格
- 不做复杂物流
- 不做计重
- 不做阶梯价
- 不做独立电商首页/搜索模型

而你当前需求恰好相反，因此**不推荐**

### 4.2 方案 B：新建电商域模型，交易主干择优复用

### 设计思路

- 新建电商店铺表
- 新建电商商品 SPU/SKU 体系
- 新建价格、计重、物流模板等能力表
- 下单入口走电商确认单编排
- 订单落库时复用现有 `orders / parent_order`
- 电商特有字段落扩展表

### 优点

- 领域边界清晰
- 不污染现有本地生活门店模型
- 后续接入外部价格、物流、计重接口更顺手
- 电商可以独立演进，不被旧链路牵制
- 代码分层更清楚，测试边界也更清晰

### 缺点

- 前期建模工作量更大
- 需要补一套电商购物车和确认单逻辑
- 需要补电商订单扩展与履约编排

### 综合判断

这是当前需求下的**最优方案**

---

## 5. 推荐方案

推荐采用：**“电商商品域新建 + 交易主干复用”的混合改造方案**

一句话概括：

- **不要复用现有 `shop/product` 作为电商主表**
- **尽量复用现有订单支付主干**

### 5.1 推荐的领域边界

#### 一层：可复用的通用交易主干

- `parent_order`
- `orders`
- `payment_log`
- `refund_order`
- `refund_log`
- `mq_local_message`
- 支付状态机
- 退款补偿机制

#### 二层：建议新建的电商领域

- 电商店铺
- 电商商品 SPU
- 电商商品 SKU
- SKU 销售属性
- 阶梯价规则
- 计重规则
- 物流模板
- 物流渠道映射
- 电商购物车
- 电商确认单定价快照
- 电商订单扩展

### 5.2 为什么这是最平衡的方案

这样拆分后：

- 电商商品复杂度被控制在新域内
- 原有门店推荐、附近搜索基本不被破坏
- 支付、退款、父子单模型继续复用，不浪费已有建设成果
- 后续你接第三方物流、计重、阶梯价时，不需要每次都去改旧门店链路

---

## 6. 推荐的数据模型

以下是推荐的第一版表设计方向，名称可再微调

### 6.1 店铺域

#### `ec_shop`

建议字段：

- `id`
- `seller_id`
- `shop_name`
- `shop_code`
- `status`
- `shop_logo`
- `shop_banner`
- `contact_phone`
- `delivery_origin_province`
- `delivery_origin_city`
- `delivery_origin_region`
- `delivery_origin_detail`
- `default_logistics_template_id`
- `after_sale_policy`
- `create_time`
- `update_time`

说明：

- 电商店铺不要直接复用现有 `shop`
- 如确实需要与商家主体打通，可通过 `seller_id` 关联

### 6.2 商品域

#### `ec_product_spu`

建议字段：

- `id`
- `shop_id`
- `spu_name`
- `spu_code`
- `category_id`
- `brand_id`
- `main_image`
- `album_images`
- `status`
- `sale_status`
- `weight_type`
- `delivery_type`
- `description`
- `create_time`
- `update_time`

说明：

- `weight_type` 可区分固定件数商品、按重量商品、混合计费商品
- `delivery_type` 可标识快递、同城配、商家自送等

#### `ec_product_sku`

建议字段：

- `id`
- `spu_id`
- `shop_id`
- `sku_code`
- `sku_name`
- `sale_attr_json`
- `market_price`
- `sale_price`
- `stock`
- `available_stock`
- `frozen_stock`
- `status`
- `weight_value`
- `weight_unit`
- `volume_value`
- `barcode`
- `image_url`
- `create_time`
- `update_time`

说明：

- 电商下单最小单位建议锁定到 SKU
- 如果存在按重量售卖，也建议最终结算落到 SKU，再通过扩展字段记录计重结果

#### `ec_product_ladder_price`

建议字段：

- `id`
- `sku_id`
- `min_quantity`
- `max_quantity`
- `price_type`
- `ladder_price`
- `status`

说明：

- 支持按购买件数触发阶梯价
- 后续如需按重量阶梯价，可再扩 `min_weight/max_weight`

#### `ec_product_weight_rule`

建议字段：

- `id`
- `sku_id`
- `pricing_weight_type`
- `weight_precision`
- `min_weight`
- `max_weight`
- `step_weight`
- `tare_weight`
- `rounding_mode`
- `ext_config_json`

说明：

- 用来承接后续外部计重接口返回值
- 本地先保存口径和兜底规则，避免对外部接口形成强耦合

### 6.3 物流域

#### `ec_logistics_template`

建议字段：

- `id`
- `shop_id`
- `template_name`
- `charge_mode`
- `free_shipping_threshold`
- `status`

#### `ec_logistics_channel`

建议字段：

- `id`
- `channel_code`
- `channel_name`
- `status`
- `ext_config_json`

#### `ec_logistics_template_channel`

建议字段：

- `id`
- `template_id`
- `channel_id`
- `priority`
- `enabled`
- `fee_rule_json`

说明：

- 电商运费一定要做成模板和渠道解耦
- 后续对接外部项目时，接口参数映射和渠道配置放这里最稳

### 6.4 交易域扩展

#### 复用 `orders / parent_order`

建议保留当前主表，但增加统一业务线字段：

- `biz_line`：如 `LOCAL_RETAIL`、`ECOMMERCE`

如不想直接改老表，也可以新增 `order_type` 的细分编码，但从长期看，单独引入 `biz_line` 更清晰

#### `ec_order_ext`

建议字段：

- `id`
- `order_id`
- `order_sn`
- `shop_id`
- `logistics_template_id`
- `logistics_channel_id`
- `estimated_freight`
- `final_freight`
- `weight_amount`
- `weight_unit`
- `pricing_snapshot_json`
- `logistics_snapshot_json`
- `ext_json`

#### `ec_order_item_ext`

建议字段：

- `id`
- `order_item_id`
- `order_sn`
- `sku_id`
- `spu_id`
- `sku_snapshot_json`
- `sale_attr_snapshot_json`
- `weight_snapshot_json`
- `ladder_price_snapshot_json`
- `logistics_snapshot_json`
- `ext_json`

说明：

- 不建议把计重、物流、阶梯价所有字段直接塞进 `order_item`
- 用扩展表保留快照，后续审计和售后更稳

---

## 7. 链路改造范围评估

### 7.1 可以直接复用或小改的模块

### `plaza-order`

- 父子单能力可复用
- 支付与退款主干可复用
- 本地消息表可复用
- 状态机主框架可复用

需要做的只是：

- 给订单编排增加电商入口
- 给库存预占增加电商 SKU 维度
- 给订单明细增加电商扩展快照落库

### `plaza-model` / `plaza-service`

- 现有公共基建可继续复用
- 需要新增电商实体、Mapper、Service

### 7.2 需要新建或明显重构的模块

### `plaza-shop`

当前更像本地门店商品中心，需要新增电商商品管理能力，建议拆出独立包甚至独立应用层：

- `shop.local.*`
- `shop.ec.*`

### `plaza-cart`

当前购物车按 `shopId + productId` 组织，价格直接取商品单价，不足以支撑：

- SKU
- 阶梯价
- 计重预估
- 物流预估

建议新增电商购物车模型，不与旧购物车强行混用

### `plaza-home`

当前明显面向“附近门店”，不建议直接承载电商店铺搜索

建议：

- 先不复用 `home` 作为电商首页
- 后续如果要做电商搜索，单独做 `ec_shop` / `ec_product` 检索域

### 库存域

当前库存围绕 `product_id` 聚合，需要扩成面向 `sku_id`

如果电商库存和原商品库存口径差异很大，建议新建：

- `ec_stock_aggregate`
- `ec_stock_log`
- `ec_stock_reservation`
- `ec_stock_reservation_item`

如果想复用库存预占主流程，也至少要把“锁库存对象”从 `productId` 抽象成“库存主体 ID + 业务线”

---

## 8. 下单与履约推荐流程

推荐电商下单流程如下：

1. 用户在电商购物车勾选 SKU
2. 进入确认订单页
3. 聚合读取：
   - SKU 快照
   - 阶梯价规则
   - 计重规则
   - 物流模板与可用渠道
4. 调用外部接口或本地规则进行：
   - 计重预估
   - 阶梯价命中
   - 运费试算
5. 生成确认单价格快照
6. 提交订单，落：
   - `parent_order`
   - `orders`
   - `order_item`
   - `ec_order_ext`
   - `ec_order_item_ext`
7. 库存预占
8. 支付
9. 支付成功后推进待发货
10. 后续再接物流发货、轨迹回传、签收

关键原则：

- **确认单阶段一定要形成价格快照**
- **提交订单阶段一定要落物流快照和计重快照**
- **不要在支付后再实时回查价格规则，否则后续对账会很麻烦**

---

## 9. 与外部项目接口的对接建议

你提到计重、阶梯价、物流渠道要请求其他项目，建议不要把这些调用散落在 Controller 或 Service 里，而是做成统一的领域适配层

建议新增：

- `WeightGateway`
- `PriceGateway`
- `LogisticsGateway`

每个网关只暴露本域语义：

- `queryWeight(...)`
- `quoteLadderPrice(...)`
- `queryAvailableChannels(...)`
- `quoteFreight(...)`

好处：

- 便于后续替换外部系统
- 便于做降级和超时兜底
- 便于沉淀调用日志和缓存

同时建议增加两类快照：

- **确认单快照**：用于用户提交前展示
- **订单快照**：用于最终落单、支付、售后、审计

---

## 10. 分阶段落地建议

### 第一阶段：先把模型边界立住

目标：

- 新建电商店铺/商品/价格/计重/物流基础表
- 不动原有本地门店与商品主链路

交付：

- `ec_shop`
- `ec_product_spu`
- `ec_product_sku`
- `ec_product_ladder_price`
- `ec_product_weight_rule`
- `ec_logistics_template`
- `ec_logistics_channel`
- `ec_logistics_template_channel`

### 第二阶段：补电商购物车与确认单

目标：

- 实现电商 SKU 购物车
- 接入阶梯价、计重、物流试算

交付：

- 电商购物车接口
- 确认单试算接口
- 外部网关适配层
- 价格快照模型

### 第三阶段：打通下单与支付主干

目标：

- 复用 `orders / parent_order / payment`
- 落电商扩展单据

交付：

- `biz_line`
- `ec_order_ext`
- `ec_order_item_ext`
- 电商下单编排

### 第四阶段：补齐履约与售后

目标：

- 打通发货、物流轨迹、签收、售后

交付：

- 物流发货单
- 物流轨迹同步
- 电商售后规则扩展

---

## 11. 最终建议

### 推荐结论

**推荐新建电商店铺与电商商品域，不直接复用现有 `shop/product` 主表**

### 推荐原因

- 当前 `shop/product` 明显偏本地门店模型
- 电商后续要接计重、阶梯价、物流渠道，复杂度远超当前商品模型
- 强行复用会把 `home/cart/order/stock/search` 全部污染成多业务线分支
- 新建电商域后，可以最大程度复用现有支付和订单主干，不需要全部推倒重来

### 一句话落地策略

**商品域新建，交易主干复用，订单明细扩展，履约能力独立**

---

## 12. 本方案对应的实施原则

- 原有 `shop/product` 继续服务本地生活/即时零售
- 电商能力进入独立 `ec_*` 领域
- 通用支付、退款、父子单主干尽量复用
- 外部计重、阶梯价、物流接口统一收口到网关层
- 所有价格、物流、计重结果都必须快照化

如果后续确认按这个方向推进，下一步建议先输出：

1. 电商表结构草案
2. 电商购物车与确认单接口设计
3. 电商下单时序图
