package com.aiolos.plaza.order.domain.stock.snapshot;

import com.aiolos.plaza.mapper.ProductMapper;
import com.aiolos.plaza.model.po.Product;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DbProductSnapshotReader implements ProductSnapshotReader {

    @Resource
    private ProductMapper productMapper;

    @Override
    public Map<Long, InventoryProductSnapshot> loadSnapshots(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Product> products = productMapper.selectBatchIds(productIds);
        if (products == null || products.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, InventoryProductSnapshot> productSnapshotMap = new LinkedHashMap<>();
        for (Product product : products) {
            if (product == null || product.getId() == null) {
                continue;
            }
            // DB 层只负责回源和快照映射，不承担缓存命中与回填职责
            productSnapshotMap.put(product.getId(), toSnapshot(product));
        }
        return productSnapshotMap;
    }

    InventoryProductSnapshot toSnapshot(Product product) {
        InventoryProductSnapshot snapshot = new InventoryProductSnapshot();
        snapshot.setProductId(product.getId());
        snapshot.setShopId(product.getShopId());
        snapshot.setProductName(product.getName());
        snapshot.setProductImage(product.getImageUrl());
        snapshot.setStatus(product.getStatus());
        snapshot.setStock(product.getStock());
        snapshot.setPrice(product.getPrice());
        return snapshot;
    }
}
