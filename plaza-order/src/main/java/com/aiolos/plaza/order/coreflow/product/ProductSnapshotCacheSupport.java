package com.aiolos.plaza.order.coreflow.product;

import com.alibaba.fastjson.JSON;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.model.po.Product;
import com.aiolos.plaza.order.coreflow.inventory.model.InventoryProductSnapshot;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 封装商品快照的缓存读写细节，避免 Reader 编排层直接处理 Redis key 与序列化逻辑
 */
@Slf4j
@Component
public class ProductSnapshotCacheSupport {

    @Resource
    @Qualifier("shopRedisTemplate")
    private StringRedisTemplate shopRedisTemplate;

    @Resource
    private DbProductSnapshotReader dbProductSnapshotReader;

    /**
     * 读取单个商品快照缓存；命中失败或反序列化失败时返回 `null`，由上层决定是否回源
     */
    public InventoryProductSnapshot readSnapshot(Long productId) {
        try {
            String productJson = shopRedisTemplate.opsForValue().get(RedisKeyEnum.PRODUCT_INFO.getKey(productId));
            if (productJson == null) {
                return null;
            }
            Product product = JSON.parseObject(productJson, Product.class);
            if (product == null || product.getId() == null) {
                return null;
            }
            return dbProductSnapshotReader.toSnapshot(product);
        } catch (Exception ex) {
            log.warn("读取商品缓存失败，productId={}", productId, ex);
            return null;
        }
    }

    /**
     * 把 DB 回源得到的商品快照回填到缓存，保证后续 confirm / submit / reserve 读取路径一致
     */
    public void writeSnapshot(InventoryProductSnapshot snapshot) {
        if (snapshot == null || snapshot.getProductId() == null) {
            return;
        }
        try {
            Product product = new Product();
            product.setId(snapshot.getProductId());
            product.setShopId(snapshot.getShopId());
            product.setName(snapshot.getProductName());
            product.setImageUrl(snapshot.getProductImage());
            product.setStatus(snapshot.getStatus());
            product.setStock(snapshot.getStock());
            product.setPrice(snapshot.getPrice());
            shopRedisTemplate.opsForValue().set(RedisKeyEnum.PRODUCT_INFO.getKey(snapshot.getProductId()), JSON.toJSONString(product), 1, TimeUnit.DAYS);
            shopRedisTemplate.opsForValue().setIfAbsent(RedisKeyEnum.PRODUCT_STOCK.getKey(snapshot.getProductId()), String.valueOf(snapshot.getStock()));
        } catch (Exception ex) {
            log.warn("回填商品缓存失败，productId={}", snapshot.getProductId(), ex);
        }
    }
}
