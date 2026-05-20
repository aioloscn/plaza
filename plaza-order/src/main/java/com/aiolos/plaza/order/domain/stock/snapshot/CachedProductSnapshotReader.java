package com.aiolos.plaza.order.domain.stock.snapshot;

import jakarta.annotation.Resource;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品快照读取默认先走缓存层
 * 1. 先批量尝试命中 Redis 商品缓存
 * 2. 未命中的商品再走 DB 回源
 * 3. 回源结果回填缓存，保证 confirm / submit / reserve 使用统一读取路径
 */
@Primary
@Component
public class CachedProductSnapshotReader implements ProductSnapshotReader {

    @Resource
    private DbProductSnapshotReader dbProductSnapshotReader;

    @Resource
    private ProductSnapshotCache productSnapshotCache;

    @Override
    public Map<Long, InventoryProductSnapshot> loadSnapshots(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, InventoryProductSnapshot> snapshots = new LinkedHashMap<>();
        List<Long> missIds = new ArrayList<>();
        for (Long skuId : skuIds) {
            if (skuId == null) {
                continue;
            }
            // 普通单缓存已切到商品中心快照键，调用侧统一传入真实 skuId
            InventoryProductSnapshot cachedSnapshot = productSnapshotCache.readSnapshot(skuId);
            if (cachedSnapshot != null) {
                snapshots.put(skuId, cachedSnapshot);
                continue;
            }
            missIds.add(skuId);
        }
        if (!missIds.isEmpty()) {
            Map<Long, InventoryProductSnapshot> dbSnapshots = dbProductSnapshotReader.loadSnapshots(missIds);
            dbSnapshots.forEach((skuId, snapshot) -> {
                snapshots.put(skuId, snapshot);
                productSnapshotCache.writeSnapshot(snapshot);
            });
        }
        return snapshots;
    }
}
