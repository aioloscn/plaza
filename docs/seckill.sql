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