package com.aiolos.plaza.order.domain.stock.snapshot;

import com.aiolos.plaza.enums.ProductBizType;
import com.aiolos.plaza.product.model.dto.ProductOrderSkuSnapshotDTO;
import com.aiolos.plaza.product.service.facade.ProductSnapshotFacade;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DbProductSnapshotReader implements ProductSnapshotReader {

    @Resource
    private ProductSnapshotFacade productSnapshotFacade;

    @Override
    public Map<Long, InventoryProductSnapshot> loadSnapshots(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Collections.emptyMap();
        }
        // 普通单库存回源改为直接读取统一商品中心的本地零售发布快照
        Map<Long, ProductOrderSkuSnapshotDTO> snapshotDTOMap = productSnapshotFacade.batchGetOrderSkuSnapshots(skuIds, ProductBizType.LOCAL_RETAIL);
        if (snapshotDTOMap == null || snapshotDTOMap.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, InventoryProductSnapshot> productSnapshotMap = new LinkedHashMap<>();
        for (Map.Entry<Long, ProductOrderSkuSnapshotDTO> entry : snapshotDTOMap.entrySet()) {
            ProductOrderSkuSnapshotDTO snapshotDTO = entry.getValue();
            if (snapshotDTO == null || snapshotDTO.getSkuId() == null) {
                continue;
            }
            // DB 层只负责回源和快照映射，不承担缓存命中与回填职责
            productSnapshotMap.put(entry.getKey(), toSnapshot(snapshotDTO));
        }
        return productSnapshotMap;
    }

    InventoryProductSnapshot toSnapshot(ProductOrderSkuSnapshotDTO product) {
        InventoryProductSnapshot snapshot = new InventoryProductSnapshot();
        snapshot.setSkuId(product.getSkuId());
        snapshot.setBizType(product.getBizType());
        snapshot.setShopId(product.getShopId());
        snapshot.setProductName(product.getSkuName());
        snapshot.setProductImage(product.getImageUrl());
        snapshot.setStatus(product.getStatus());
        snapshot.setStock(product.getAvailableStock());
        snapshot.setPrice(product.getSalePrice());
        return snapshot;
    }
}
