-- 商品域最终版表结构
-- 设计原则：
-- 1. 主商品表统一，不再按外卖/电商各建一套 SPU/SKU 主表
-- 2. 通过发布关系表区分业务场景，通过扩展表承载场景差异
-- 3. 旧 product 表不再作为长期核心模型

DROP TABLE IF EXISTS `product_publish_rel`;
DROP TABLE IF EXISTS `product_sku_sale_attr`;
DROP TABLE IF EXISTS `product_sale_attr`;
DROP TABLE IF EXISTS `product_media`;
DROP TABLE IF EXISTS `product_local_ext`;
DROP TABLE IF EXISTS `product_ecommerce_ext`;
DROP TABLE IF EXISTS `product_weight_rule`;
DROP TABLE IF EXISTS `product_ladder_price`;
DROP TABLE IF EXISTS `product_sku`;
DROP TABLE IF EXISTS `product_spu`;

CREATE TABLE `product_spu` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` bigint(20) NOT NULL COMMENT '归属店铺ID',
  `spu_name` varchar(200) NOT NULL COMMENT 'SPU名称',
  `spu_code` varchar(64) DEFAULT NULL COMMENT 'SPU编码',
  `category_id` bigint(20) DEFAULT NULL COMMENT '类目ID',
  `brand_id` bigint(20) DEFAULT NULL COMMENT '品牌ID',
  `product_type` int(11) NOT NULL DEFAULT '1' COMMENT '商品类型：1-普通商品，2-计重商品，3-套餐商品',
  `source_type` int(11) NOT NULL DEFAULT '1' COMMENT '来源类型：1-商家录入，2-平台导入，3-外部同步',
  `main_image` varchar(500) DEFAULT NULL COMMENT '主图',
  `album_images` text COMMENT '图集JSON',
  `status` int(11) NOT NULL DEFAULT '1' COMMENT '状态：0-禁用，1-启用',
  `description` text COMMENT '商品描述',
  `ext_config_json` text COMMENT '扩展配置JSON',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_spu_code` (`spu_code`),
  KEY `idx_shop_status` (`shop_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一商品SPU主表';

CREATE TABLE `product_sku` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `spu_id` bigint(20) NOT NULL COMMENT 'SPU ID',
  `shop_id` bigint(20) NOT NULL COMMENT '归属店铺ID',
  `sku_code` varchar(64) DEFAULT NULL COMMENT 'SKU编码',
  `sku_name` varchar(200) NOT NULL COMMENT 'SKU名称',
  `bar_code` varchar(64) DEFAULT NULL COMMENT '条码',
  `market_price` decimal(10,2) DEFAULT NULL COMMENT '划线价',
  `sale_price` decimal(10,2) NOT NULL COMMENT '基础销售价',
  `cost_price` decimal(10,2) DEFAULT NULL COMMENT '成本价',
  `total_stock` int(11) NOT NULL DEFAULT '0' COMMENT '总库存',
  `available_stock` int(11) NOT NULL DEFAULT '0' COMMENT '可用库存',
  `frozen_stock` int(11) NOT NULL DEFAULT '0' COMMENT '冻结库存',
  `default_weight` decimal(10,3) DEFAULT NULL COMMENT '默认重量值',
  `weight_unit` varchar(16) DEFAULT NULL COMMENT '重量单位',
  `default_volume` decimal(10,3) DEFAULT NULL COMMENT '默认体积值',
  `volume_unit` varchar(16) DEFAULT NULL COMMENT '体积单位',
  `image_url` varchar(500) DEFAULT NULL COMMENT 'SKU图片',
  `status` int(11) NOT NULL DEFAULT '1' COMMENT '状态：0-停用，1-启用',
  `ext_config_json` text COMMENT '扩展配置JSON',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_code` (`sku_code`),
  KEY `idx_spu_status` (`spu_id`,`status`),
  KEY `idx_shop_status` (`shop_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一商品SKU主表';

CREATE TABLE `product_media` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `spu_id` bigint(20) NOT NULL COMMENT 'SPU ID',
  `sku_id` bigint(20) DEFAULT NULL COMMENT 'SKU ID，空表示SPU级素材',
  `media_type` int(11) NOT NULL DEFAULT '1' COMMENT '素材类型：1-主图，2-图集，3-视频，4-详情图',
  `media_url` varchar(500) NOT NULL COMMENT '素材地址',
  `sort_no` int(11) NOT NULL DEFAULT '0' COMMENT '排序号',
  `status` int(11) NOT NULL DEFAULT '1' COMMENT '状态：0-停用，1-启用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_spu_sku_type` (`spu_id`,`sku_id`,`media_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品素材表';

CREATE TABLE `product_sale_attr` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `spu_id` bigint(20) NOT NULL COMMENT 'SPU ID',
  `attr_name` varchar(64) NOT NULL COMMENT '销售属性名',
  `attr_value` varchar(128) NOT NULL COMMENT '销售属性值',
  `sort_no` int(11) NOT NULL DEFAULT '0' COMMENT '排序号',
  `status` int(11) NOT NULL DEFAULT '1' COMMENT '状态：0-停用，1-启用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_spu_status` (`spu_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SPU销售属性候选值表';

CREATE TABLE `product_sku_sale_attr` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sku_id` bigint(20) NOT NULL COMMENT 'SKU ID',
  `sale_attr_id` bigint(20) NOT NULL COMMENT '销售属性ID',
  `attr_name` varchar(64) NOT NULL COMMENT '销售属性名',
  `attr_value` varchar(128) NOT NULL COMMENT '销售属性值',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_sale_attr` (`sku_id`,`sale_attr_id`),
  KEY `idx_sku_id` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU销售属性映射表';

CREATE TABLE `product_publish_rel` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` bigint(20) NOT NULL COMMENT '店铺ID',
  `spu_id` bigint(20) NOT NULL COMMENT 'SPU ID',
  `sku_id` bigint(20) NOT NULL COMMENT 'SKU ID',
  `biz_type` int(11) NOT NULL COMMENT '业务类型：1-外卖/即时零售，2-电商',
  `channel_sale_price` decimal(10,2) DEFAULT NULL COMMENT '场景销售价，空表示使用SKU基础销售价',
  `sale_status` int(11) NOT NULL DEFAULT '1' COMMENT '销售状态：0-下架，1-上架',
  `visible_status` int(11) NOT NULL DEFAULT '1' COMMENT '可见状态：0-隐藏，1-可见',
  `sort_no` int(11) NOT NULL DEFAULT '0' COMMENT '排序号',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_shop_sku_biz` (`shop_id`,`sku_id`,`biz_type`),
  KEY `idx_spu_biz_status` (`spu_id`,`biz_type`,`sale_status`),
  KEY `idx_shop_biz_status` (`shop_id`,`biz_type`,`sale_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品场景发布关系表';

CREATE TABLE `product_ladder_price` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sku_id` bigint(20) NOT NULL COMMENT 'SKU ID',
  `biz_type` int(11) NOT NULL COMMENT '业务类型：1-外卖/即时零售，2-电商',
  `min_quantity` int(11) NOT NULL COMMENT '最小数量',
  `max_quantity` int(11) DEFAULT NULL COMMENT '最大数量，空表示无上限',
  `ladder_price` decimal(10,2) NOT NULL COMMENT '阶梯价',
  `status` int(11) NOT NULL DEFAULT '1' COMMENT '状态：0-停用，1-启用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_sku_biz_status` (`sku_id`,`biz_type`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品场景阶梯价规则表';

CREATE TABLE `product_weight_rule` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sku_id` bigint(20) NOT NULL COMMENT 'SKU ID',
  `biz_type` int(11) NOT NULL COMMENT '业务类型：1-外卖/即时零售，2-电商',
  `pricing_weight_type` int(11) NOT NULL DEFAULT '1' COMMENT '计重类型：1-固定重量，2-动态称重，3-区间称重',
  `weight_precision` int(11) NOT NULL DEFAULT '0' COMMENT '重量精度位数',
  `min_weight` decimal(10,3) DEFAULT NULL COMMENT '最小重量',
  `max_weight` decimal(10,3) DEFAULT NULL COMMENT '最大重量',
  `step_weight` decimal(10,3) DEFAULT NULL COMMENT '步进重量',
  `rounding_mode` int(11) NOT NULL DEFAULT '1' COMMENT '取整模式：1-四舍五入，2-向上取整，3-向下取整',
  `status` int(11) NOT NULL DEFAULT '1' COMMENT '状态：0-停用，1-启用',
  `ext_config_json` text COMMENT '扩展配置JSON',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_sku_biz_status` (`sku_id`,`biz_type`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品场景计重规则表';

CREATE TABLE `product_local_ext` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `spu_id` bigint(20) NOT NULL COMMENT 'SPU ID',
  `packing_fee` decimal(10,2) DEFAULT NULL COMMENT '打包费',
  `unit_name` varchar(32) DEFAULT NULL COMMENT '展示单位，如份、盒、杯',
  `min_purchase_qty` int(11) DEFAULT NULL COMMENT '最小起售数量',
  `max_purchase_qty` int(11) DEFAULT NULL COMMENT '单次限购数量',
  `support_takeaway` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否支持外卖：0-否，1-是',
  `support_self_pickup` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否支持自提：0-否，1-是',
  `sale_time_json` text COMMENT '售卖时段JSON',
  `tag_json` text COMMENT '标签JSON',
  `ext_config_json` text COMMENT '扩展配置JSON',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_spu_id` (`spu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外卖/即时零售商品扩展表';

CREATE TABLE `product_ecommerce_ext` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `spu_id` bigint(20) NOT NULL COMMENT 'SPU ID',
  `logistics_template_id` bigint(20) DEFAULT NULL COMMENT '物流模板ID',
  `delivery_origin_province` varchar(64) DEFAULT NULL COMMENT '发货省',
  `delivery_origin_city` varchar(64) DEFAULT NULL COMMENT '发货市',
  `delivery_origin_region` varchar(64) DEFAULT NULL COMMENT '发货区',
  `delivery_origin_detail` varchar(255) DEFAULT NULL COMMENT '发货详细地址',
  `after_sale_policy` text COMMENT '售后策略',
  `delivery_channel_json` text COMMENT '可用物流渠道JSON',
  `ext_config_json` text COMMENT '扩展配置JSON',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_spu_id` (`spu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电商商品扩展表';
