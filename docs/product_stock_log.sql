CREATE TABLE IF NOT EXISTS `product_stock_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `product_id` bigint(20) NOT NULL COMMENT '商品ID',
  `order_sn` varchar(64) DEFAULT NULL COMMENT '关联订单号',
  `amount` int(11) NOT NULL COMMENT '变动数量（正数为增加，负数为扣减，支付确认可为0）',
  `type` tinyint(4) NOT NULL COMMENT '操作类型：1-下单扣减，2-取消回滚，3-后台修改，4-预占冻结，5-支付确认，6-预占释放，7-预占过期',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_product_id` (`product_id`),
  KEY `idx_order_sn` (`order_sn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品库存操作记录表';
