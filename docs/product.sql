CREATE TABLE IF NOT EXISTS `product` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `shop_id` bigint(20) NOT NULL COMMENT '店铺ID',
  `name` varchar(255) NOT NULL COMMENT '商品名称',
  `price` decimal(10,2) NOT NULL COMMENT '价格',
  `stock` int(11) NOT NULL DEFAULT '0' COMMENT '库存',
  `description` text COMMENT '描述',
  `image_url` varchar(255) DEFAULT NULL COMMENT '图片URL',
  `status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '状态 0:下架 1:上架',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_shop_id` (`shop_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

INSERT INTO `product` (`shop_id`, `name`, `price`, `stock`, `description`, `image_url`, `status`) VALUES 
(474, '拿铁咖啡', 35.00, 100, '经典拿铁，香浓牛奶', 'https://example.com/latte.jpg', 1),
(474, '美式咖啡', 25.00, 100, '纯正美式，提神醒脑', 'https://example.com/americano.jpg', 1),
(190, '红烧肉', 68.00, 50, '本帮红烧肉，肥而不腻', 'https://example.com/pork.jpg', 1),
(463, '波霸奶茶', 18.00, 200, 'Q弹波霸，奶香浓郁', 'https://example.com/boba.jpg', 1),
(475, '四季春茶', 15.00, 150, '清新四季春，解渴', 'https://example.com/tea.jpg', 1);
