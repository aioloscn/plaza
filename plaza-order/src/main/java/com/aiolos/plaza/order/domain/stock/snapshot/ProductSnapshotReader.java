package com.aiolos.plaza.order.domain.stock.snapshot;

import java.util.List;
import java.util.Map;

/**
 * 订单域读取商品快照的统一入口
 * 调用方只关心“拿到可下单快照”，不关心底层来自缓存、数据库还是远程服务
 */
public interface ProductSnapshotReader {
    Map<Long, InventoryProductSnapshot> loadSnapshots(List<Long> productIds);
}
