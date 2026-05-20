package com.aiolos.plaza.order.domain.stock.snapshot;

import java.util.List;
import java.util.Map;

/**
 * 订单域读取商品快照的统一入口
 * 调用方只关心“拿到可下单快照”，不关心底层来自缓存、数据库还是远程服务
 */
public interface ProductSnapshotReader {
    /**
     * 返回值的 key 与入参保持一致，统一按 skuId 组织
     * 本地零售兼容阶段，旧 product.id 会暂时作为 skuId 使用
     */
    Map<Long, InventoryProductSnapshot> loadSnapshots(List<Long> skuIds);
}
