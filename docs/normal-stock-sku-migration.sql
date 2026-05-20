-- 普通单库存链路开发期重建脚本
-- 适用前提：
-- 1. 当前处于开发阶段，允许清理旧数据
-- 2. 普通单库存主链路统一围绕真实 sku_id 建模
-- 3. 禁止再采用 `sku_id = product_id` 的错误回填方式

-- 方案一：直接清空并重建普通库存相关表
TRUNCATE TABLE `stock_reservation_item`;
TRUNCATE TABLE `stock_reservation`;
TRUNCATE TABLE `product_stock_log`;
TRUNCATE TABLE `product_stock_aggregate`;

ALTER TABLE `product_stock_aggregate`
    DROP COLUMN IF EXISTS `product_id`,
    ADD COLUMN IF NOT EXISTS `sku_id` bigint(20) NOT NULL COMMENT '商品SKU ID' AFTER `id`,
    DROP INDEX IF EXISTS `uk_product_id`,
    ADD UNIQUE KEY `uk_sku_id` (`sku_id`);

ALTER TABLE `stock_reservation_item`
    DROP COLUMN IF EXISTS `product_id`,
    ADD COLUMN IF NOT EXISTS `sku_id` bigint(20) NOT NULL COMMENT '商品SKU ID' AFTER `activity_id`,
    DROP INDEX IF EXISTS `uk_reservation_product`,
    ADD UNIQUE KEY `uk_reservation_sku` (`reservation_no`, `sku_id`);

ALTER TABLE `product_stock_log`
    DROP COLUMN IF EXISTS `product_id`,
    ADD COLUMN IF NOT EXISTS `sku_id` bigint(20) NOT NULL COMMENT '商品SKU ID' AFTER `id`,
    DROP INDEX IF EXISTS `idx_product_id`,
    ADD INDEX `idx_sku_id` (`sku_id`);

-- 方案二：如果不方便直接重建，则必须先完成旧商品到真实 product_sku.id 的映射
-- 映射完成前，不要把旧 product_id 直接写入 sku_id
