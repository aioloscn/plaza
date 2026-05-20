-- 购物车表从 product_id 迁移到 sku_id
-- 执行前请先在低峰期备份 cart_item

ALTER TABLE `cart_item`
    ADD COLUMN IF NOT EXISTS `sku_id` bigint(20) NULL COMMENT '商品SKU ID' AFTER `shop_id`;

ALTER TABLE `cart_item`
    ADD COLUMN IF NOT EXISTS `biz_type` tinyint(4) NOT NULL DEFAULT '1' COMMENT '业务类型 1:外卖/即时零售 2:电商' AFTER `sku_id`;

UPDATE `cart_item`
SET `sku_id` = `product_id`
WHERE `sku_id` IS NULL
  AND `product_id` IS NOT NULL;

ALTER TABLE `cart_item`
    MODIFY COLUMN `sku_id` bigint(20) NOT NULL COMMENT '商品SKU ID';

ALTER TABLE `cart_item`
    DROP INDEX `idx_user_product`,
    ADD INDEX `idx_user_sku` (`user_id`, `biz_type`, `sku_id`);

-- 确认业务代码已全部切到 sku_id 后，再执行下面语句删除旧字段
-- ALTER TABLE `cart_item` DROP COLUMN `product_id`;
