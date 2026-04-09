
DROP TABLE IF EXISTS `orders`;
CREATE TABLE `orders` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_sn` varchar(64) DEFAULT NULL COMMENT '订单号',
  `reservation_no` varchar(64) DEFAULT NULL COMMENT '库存预占单号',
  `parent_order_sn` varchar(64) DEFAULT NULL COMMENT '父订单号（支付单号）',
  `user_id` bigint(20) DEFAULT NULL COMMENT '用户ID',
  `shop_id` bigint(20) DEFAULT NULL COMMENT '店铺ID',
  `order_type` int(1) DEFAULT '1' COMMENT '订单类型：1->普通订单；2->秒杀订单',
  `activity_id` bigint(20) DEFAULT NULL COMMENT '秒杀活动ID',
  `total_amount` decimal(10,2) DEFAULT NULL COMMENT '订单总金额',
  `pay_amount` decimal(10,2) DEFAULT NULL COMMENT '应付金额',
  `freight_amount` decimal(10,2) DEFAULT NULL COMMENT '运费金额',
  `promotion_amount` decimal(10,2) DEFAULT NULL COMMENT '促销优化金额',
  `pay_type` int(1) DEFAULT NULL COMMENT '支付方式：0->未支付；1->支付宝；2->微信',
  `status` int(1) DEFAULT NULL COMMENT '展示态/兼容态：0->待付款；1->待发货；2->已发货；3->已完成；4->已关闭；5->无效订单；6->支付中；7->关闭确认中；8->支付补偿中；9->退款中；10->已退款；11->退款失败；12->锁库存中',
  `payment_status` int(1) DEFAULT NULL COMMENT '支付维度状态：0->未支付；1->支付中；2->部分已支付；3->已支付；4->支付补偿中；5->退款中；6->已退款；7->退款失败',
  `fulfillment_status` int(1) DEFAULT NULL COMMENT '履约维度状态：0->待履约；1->锁库存中；2->待发货；3->部分已发货；4->已发货；5->已完成；6->已关闭',
  `aftersale_status` int(1) DEFAULT NULL COMMENT '售后维度状态：0->无售后；1->退款中；2->部分已退款；3->已退款；4->退款失败',
  `address_id` bigint(20) DEFAULT NULL COMMENT '收货地址ID',
  `receiver_name` varchar(100) DEFAULT NULL COMMENT '收货人姓名',
  `receiver_phone` varchar(32) DEFAULT NULL COMMENT '收货人电话',
  `receiver_province` varchar(32) DEFAULT NULL COMMENT '省份/直辖市',
  `receiver_city` varchar(32) DEFAULT NULL COMMENT '城市',
  `receiver_region` varchar(32) DEFAULT NULL COMMENT '区',
  `receiver_detail_address` varchar(200) DEFAULT NULL COMMENT '详细地址',
  `note` varchar(500) DEFAULT NULL COMMENT '订单备注',
  `confirm_status` int(1) DEFAULT NULL COMMENT '确认收货状态：0->未确认；1->已确认',
  `delete_status` int(1) DEFAULT '0' COMMENT '删除状态：0->未删除；1->已删除',
  `payment_time` datetime DEFAULT NULL COMMENT '支付时间',
  `delivery_time` datetime DEFAULT NULL COMMENT '发货时间',
  `receive_time` datetime DEFAULT NULL COMMENT '确认收货时间',
  `comment_time` datetime DEFAULT NULL COMMENT '评价时间',
  `create_time` datetime DEFAULT NULL COMMENT '提交时间',
  `update_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_sn` (`order_sn`),
  UNIQUE KEY `uk_reservation_no` (`reservation_no`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

DROP TABLE IF EXISTS `parent_order`;
CREATE TABLE `parent_order` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `parent_order_sn` varchar(64) DEFAULT NULL COMMENT '父订单编号（支付单号）',
  `user_id` bigint(20) DEFAULT NULL COMMENT '用户ID',
  `total_amount` decimal(10,2) DEFAULT NULL COMMENT '总金额',
  `pay_amount` decimal(10,2) DEFAULT NULL COMMENT '应付总金额',
  `status` int(1) DEFAULT NULL COMMENT '展示态/兼容态：0->待付款；1->待发货；2->已发货；3->已完成；4->已关闭；5->无效订单；6->支付中；7->关闭确认中；8->支付补偿中；9->退款中；10->已退款；11->退款失败；12->锁库存中',
  `payment_status` int(1) DEFAULT NULL COMMENT '支付维度状态：0->未支付；1->支付中；2->部分已支付；3->已支付；4->支付补偿中；5->退款中；6->已退款；7->退款失败',
  `fulfillment_status` int(1) DEFAULT NULL COMMENT '履约维度状态：0->待履约；1->锁库存中；2->待发货；3->部分已发货；4->已发货；5->已完成；6->已关闭',
  `aftersale_status` int(1) DEFAULT NULL COMMENT '售后维度状态：0->无售后；1->退款中；2->部分已退款；3->已退款；4->退款失败',
  `pay_type` int(1) DEFAULT NULL COMMENT '支付方式：1->支付宝；2->微信',
  `order_type` int(1) DEFAULT '1' COMMENT '订单类型：1->普通订单；2->秒杀订单',
  `trade_no` varchar(64) DEFAULT NULL COMMENT '第三方支付流水号',
  `buyer_id` varchar(64) DEFAULT NULL COMMENT '买家在支付平台的账号/ID',
  `payment_time` datetime DEFAULT NULL COMMENT '支付时间',
  `delete_status` int(1) DEFAULT '0' COMMENT '删除状态：0->未删除；1->已删除',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_parent_order_sn` (`parent_order_sn`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='父订单表';

DROP TABLE IF EXISTS `order_item`;
CREATE TABLE `order_item` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `order_id` bigint(20) DEFAULT NULL COMMENT '订单id',
  `order_sn` varchar(64) DEFAULT NULL COMMENT '订单编号',
  `product_id` bigint(20) DEFAULT NULL COMMENT '商品id',
  `product_pic` varchar(500) DEFAULT NULL COMMENT '商品图片',
  `product_name` varchar(200) DEFAULT NULL COMMENT '商品名称',
  `product_brand` varchar(200) DEFAULT NULL COMMENT '商品品牌',
  `product_sn` varchar(64) DEFAULT NULL COMMENT '商品条码',
  `product_price` decimal(10,2) DEFAULT NULL COMMENT '销售价格',
  `product_quantity` int(11) DEFAULT NULL COMMENT '购买数量',
  `product_sku_id` bigint(20) DEFAULT NULL COMMENT '商品sku编号',
  `product_sku_code` varchar(50) DEFAULT NULL COMMENT '商品sku条码',
  `product_category_id` bigint(20) DEFAULT NULL COMMENT '商品分类id',
  `promotion_name` varchar(200) DEFAULT NULL COMMENT '商品促销名称',
  `promotion_amount` decimal(10,2) DEFAULT NULL COMMENT '商品促销分解金额',
  `coupon_amount` decimal(10,2) DEFAULT NULL COMMENT '优惠券优惠分解金额',
  `integration_amount` decimal(10,2) DEFAULT NULL COMMENT '积分优惠分解金额',
  `real_amount` decimal(10,2) DEFAULT NULL COMMENT '该商品经过优惠后的分解金额',
  `gift_integration` int(11) DEFAULT '0' COMMENT '商品赠送积分',
  `gift_growth` int(11) DEFAULT '0' COMMENT '商品赠送成长值',
  `product_attr` varchar(500) DEFAULT NULL COMMENT '商品销售属性:[{"key":"颜色","value":"颜色"},{"key":"容量","value":"4G"}]',
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='订单中所包含的商品';

DROP TABLE IF EXISTS `product_stock_aggregate`;
CREATE TABLE `product_stock_aggregate` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `product_id` bigint(20) NOT NULL,
  `available_stock` int(11) NOT NULL DEFAULT '0',
  `frozen_stock` int(11) NOT NULL DEFAULT '0',
  `confirmed_stock` int(11) NOT NULL DEFAULT '0',
  `version` int(11) NOT NULL DEFAULT '0',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_id` (`product_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='库存聚合表';

DROP TABLE IF EXISTS `seckill_stock_aggregate`;
CREATE TABLE `seckill_stock_aggregate` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `activity_id` bigint(20) NOT NULL COMMENT '秒杀活动ID',
  `product_id` bigint(20) NOT NULL COMMENT '商品ID',
  `available_stock` int(11) NOT NULL DEFAULT '0',
  `frozen_stock` int(11) NOT NULL DEFAULT '0',
  `confirmed_stock` int(11) NOT NULL DEFAULT '0',
  `version` int(11) NOT NULL DEFAULT '0',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_product` (`activity_id`,`product_id`),
  KEY `idx_product_id` (`product_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='秒杀库存聚合表';

DROP TABLE IF EXISTS `stock_reservation`;
CREATE TABLE `stock_reservation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `reservation_no` varchar(64) NOT NULL,
  `order_sn` varchar(64) NOT NULL,
  `user_id` bigint(20) DEFAULT NULL,
  `stock_scope` int(1) NOT NULL DEFAULT '1' COMMENT '库存池范围：1-普通库存池，2-秒杀库存池',
  `activity_id` bigint(20) DEFAULT NULL COMMENT '秒杀活动ID（普通库存池为空）',
  `status` int(1) NOT NULL COMMENT '0-冻结中 1-已确认 2-已释放 3-已过期',
  `expire_time` datetime DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservation_no` (`reservation_no`),
  UNIQUE KEY `uk_order_sn` (`order_sn`),
  KEY `idx_user_id_status` (`user_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='库存预占单';

DROP TABLE IF EXISTS `stock_reservation_item`;
CREATE TABLE `stock_reservation_item` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `reservation_no` varchar(64) NOT NULL,
  `order_sn` varchar(64) NOT NULL,
  `stock_scope` int(1) NOT NULL DEFAULT '1' COMMENT '库存池范围：1-普通库存池，2-秒杀库存池',
  `activity_id` bigint(20) DEFAULT NULL COMMENT '秒杀活动ID（普通库存池为空）',
  `product_id` bigint(20) NOT NULL,
  `quantity` int(11) NOT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservation_product` (`reservation_no`,`product_id`),
  KEY `idx_order_sn` (`order_sn`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='库存预占明细';

DROP TABLE IF EXISTS `mq_local_message`;
CREATE TABLE `mq_local_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `topic` varchar(128) NOT NULL COMMENT '消息主题',
  `tag` varchar(128) DEFAULT NULL COMMENT '扩展标签',
  `message_type` varchar(64) NOT NULL COMMENT '消息类型',
  `content` text NOT NULL COMMENT '消息内容',
  `state` int(11) NOT NULL DEFAULT '0' COMMENT '状态：0-新建 1-成功 2-失败 3-处理中 4-死信',
  `retry_count` int(11) NOT NULL DEFAULT '0' COMMENT '已重试次数',
  `next_retry_time` datetime DEFAULT NULL COMMENT '下次可重试时间',
  `max_retry_count` int(11) NOT NULL DEFAULT '5' COMMENT '最大重试次数',
  `business_key` varchar(128) DEFAULT NULL COMMENT '业务键',
  `fail_reason` varchar(500) DEFAULT NULL COMMENT '最近一次失败原因',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_topic_business_key` (`topic`,`business_key`),
  KEY `idx_state_next_retry` (`state`,`next_retry_time`),
  KEY `idx_message_type_state` (`message_type`,`state`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';

DROP TABLE IF EXISTS `payment_log`;
CREATE TABLE `payment_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_sn` varchar(64) NOT NULL COMMENT '父订单号',
  `pay_type` int(1) DEFAULT NULL COMMENT '支付方式',
  `trade_no` varchar(64) DEFAULT NULL COMMENT '第三方支付流水号',
  `total_amount` decimal(10,2) DEFAULT NULL COMMENT '支付金额',
  `buyer_id` varchar(64) DEFAULT NULL COMMENT '买家在支付平台的账号/ID',
  `payment_time` datetime DEFAULT NULL COMMENT '支付时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_trade_no` (`order_sn`,`trade_no`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='支付流水日志表';

DROP TABLE IF EXISTS `payment_compensation_task`;
CREATE TABLE `payment_compensation_task` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_no` varchar(64) NOT NULL COMMENT '补偿任务号',
  `business_key` varchar(128) NOT NULL COMMENT '幂等业务键',
  `compensation_type` int(11) NOT NULL COMMENT '补偿类型：1->支付结果查询，2->退款执行，3->退款对账',
  `parent_order_sn` varchar(64) DEFAULT NULL COMMENT '父订单号',
  `order_sn` varchar(64) DEFAULT NULL COMMENT '子订单号',
  `trade_no` varchar(64) DEFAULT NULL COMMENT '第三方支付流水号',
  `refund_request_no` varchar(64) DEFAULT NULL COMMENT '退款请求号',
  `status` int(11) NOT NULL DEFAULT '0' COMMENT '任务状态：0->待执行，1->处理中，2->已完成，3->待重试，4->待人工介入，5->已关闭',
  `retry_count` int(11) NOT NULL DEFAULT '0' COMMENT '重试次数',
  `max_retry_count` int(11) NOT NULL DEFAULT '8' COMMENT '最大重试次数',
  `next_retry_time` datetime DEFAULT NULL COMMENT '下次重试时间',
  `reason_code` varchar(64) DEFAULT NULL COMMENT '原因编码',
  `third_party_status` varchar(64) DEFAULT NULL COMMENT '最近一次第三方状态',
  `fail_reason` varchar(500) DEFAULT NULL COMMENT '最近一次失败原因',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_no` (`task_no`),
  UNIQUE KEY `uk_business_key` (`business_key`),
  KEY `idx_status_next_retry` (`status`,`next_retry_time`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='支付补偿任务表';

DROP TABLE IF EXISTS `refund_order`;
CREATE TABLE `refund_order` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `refund_request_no` varchar(64) NOT NULL COMMENT '退款请求号',
  `parent_order_sn` varchar(64) NOT NULL COMMENT '父订单号',
  `trade_no` varchar(64) DEFAULT NULL COMMENT '第三方支付流水号',
  `pay_type` int(1) DEFAULT NULL COMMENT '支付方式',
  `refund_amount` decimal(10,2) DEFAULT NULL COMMENT '退款金额',
  `status` int(11) NOT NULL DEFAULT '0' COMMENT '退款单状态：0-待退款，1-退款处理中，2-退款成功，3-退款失败，4-待人工介入，5-已关闭',
  `retry_count` int(11) NOT NULL DEFAULT '0' COMMENT '重试次数',
  `max_retry_count` int(11) NOT NULL DEFAULT '8' COMMENT '最大重试次数',
  `next_retry_time` datetime DEFAULT NULL COMMENT '下次重试时间',
  `reason_code` varchar(64) DEFAULT NULL COMMENT '退款原因编码',
  `fail_reason` varchar(500) DEFAULT NULL COMMENT '最近一次失败原因',
  `refund_time` datetime DEFAULT NULL COMMENT '退款成功时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_request_no` (`refund_request_no`),
  KEY `idx_parent_order_status` (`parent_order_sn`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='退款单表';

DROP TABLE IF EXISTS `refund_log`;
CREATE TABLE `refund_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `refund_request_no` varchar(64) NOT NULL COMMENT '退款请求号',
  `action_type` varchar(32) DEFAULT NULL COMMENT '动作类型：APPLY/QUERY/CALLBACK/RECONCILE',
  `action_status` varchar(32) DEFAULT NULL COMMENT '动作结果：RECEIVED/SUCCESS/PROCESSING',
  `request_payload` text COMMENT '请求报文',
  `response_payload` text COMMENT '响应报文',
  `message` varchar(500) DEFAULT NULL COMMENT '补充说明',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_refund_request_no` (`refund_request_no`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb4 COMMENT='退款日志表';
