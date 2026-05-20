-- 订单相关表 sku 语义修正脚本
-- 注意：
-- 1. `product_sku_id` 只能存真实 `product_sku.id`
-- 2. 禁止再把旧 `product_id` 直接回填到 `product_sku_id`
-- 3. 开发阶段如允许清理数据，推荐直接清空订单明细后按新口径重新落单

ALTER TABLE `order_item`
    ADD INDEX `idx_order_item_product_sku_id` (`product_sku_id`);

-- 如需保留历史数据，请先构建旧商品到真实 SKU 的映射表，再执行类似如下回填：
-- UPDATE `order_item` oi
-- JOIN `legacy_product_sku_mapping` m ON m.legacy_product_id = oi.product_id
-- SET oi.product_sku_id = m.sku_id
-- WHERE oi.product_sku_id IS NULL;
