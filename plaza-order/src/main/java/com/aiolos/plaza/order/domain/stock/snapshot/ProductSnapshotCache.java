package com.aiolos.plaza.order.domain.stock.snapshot;

import com.alibaba.fastjson.JSON;
import com.aiolos.plaza.enums.ProductBizType;
import com.aiolos.plaza.enums.RedisKeyEnum;

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
public class ProductSnapshotCache {

    @Resource
    @Qualifier("shopRedisTemplate")
    private StringRedisTemplate shopRedisTemplate;

    /**
     * 读取单个商品快照缓存；命中失败或反序列化失败时返回 `null`，由上层决定是否回源
     */
    public InventoryProductSnapshot readSnapshot(Long skuId) {
        try {
            // 普通单缓存键切到“业务线 + skuId”，避免再与旧 product.id 缓存串号
            String productJson = shopRedisTemplate.opsForValue().get(RedisKeyEnum.PRODUCT_SNAPSHOT_INFO
                    .getKey(ProductBizType.LOCAL_RETAIL.getCode(), skuId));
            if (productJson == null) {
                return null;
            }
            InventoryProductSnapshot snapshot = JSON.parseObject(productJson, InventoryProductSnapshot.class);
            if (snapshot == null || snapshot.getSkuId() == null) {
                return null;
            }
            return snapshot;
        } catch (Exception ex) {
            log.warn("读取商品缓存失败，skuId={}", skuId, ex);
            return null;
        }
    }

    /**
     * 把 DB 回源得到的商品快照回填到缓存，保证后续 confirm / submit / reserve 读取路径一致
     */
    public void writeSnapshot(InventoryProductSnapshot snapshot) {
        if (snapshot == null || snapshot.getSkuId() == null) {
            return;
        }
        try {
            shopRedisTemplate.opsForValue().set(RedisKeyEnum.PRODUCT_SNAPSHOT_INFO
                    .getKey(ProductBizType.LOCAL_RETAIL.getCode(), snapshot.getSkuId()), JSON.toJSONString(snapshot), 1, TimeUnit.DAYS);
        } catch (Exception ex) {
            log.warn("回填商品缓存失败，skuId={}", snapshot.getSkuId(), ex);
        }
    }
}
