package com.aiolos.plaza.shop.service.impl;

import com.aiolos.plaza.enums.ProductBizType;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.mapper.ProductPublishRelMapper;
import com.aiolos.plaza.mapper.ProductSkuMapper;
import com.aiolos.plaza.model.po.ProductPublishRel;
import com.aiolos.plaza.model.po.ProductSku;
import com.aiolos.plaza.shop.service.ProductCenterCacheService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCenterCacheServiceImpl implements ProductCenterCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ProductPublishRelMapper productPublishRelMapper;
    private final ProductSkuMapper productSkuMapper;

    @Override
    public void evictLocalRetailSkuSnapshots(List<Long> skuIds) {
        if (CollectionUtils.isEmpty(skuIds)) {
            return;
        }
        Set<String> cacheKeys = new LinkedHashSet<>();
        for (Long skuId : skuIds) {
            if (skuId == null) {
                continue;
            }
            cacheKeys.add(RedisKeyEnum.PRODUCT_SNAPSHOT_INFO.getKey(ProductBizType.LOCAL_RETAIL.getCode(), skuId));
        }
        if (cacheKeys.isEmpty()) {
            return;
        }
        stringRedisTemplate.delete(cacheKeys);
        log.info("统一商品中心本地零售快照缓存清理完成，skuIds={}", skuIds);
    }

    @Override
    public void evictByPublishId(Long publishId) {
        if (publishId == null) {
            return;
        }
        ProductPublishRel publishRel = productPublishRelMapper.selectById(publishId);
        if (publishRel == null || publishRel.getSkuId() == null) {
            return;
        }
        if (!ProductBizType.LOCAL_RETAIL.getCode().equals(publishRel.getBizType())) {
            return;
        }
        evictLocalRetailSkuSnapshots(List.of(publishRel.getSkuId()));
    }

    @Override
    public void evictBySpuId(Long spuId) {
        if (spuId == null) {
            return;
        }
        List<ProductSku> skuList = productSkuMapper.selectList(Wrappers.<ProductSku>lambdaQuery()
                .eq(ProductSku::getSpuId, spuId));
        if (CollectionUtils.isEmpty(skuList)) {
            return;
        }
        List<Long> skuIds = new ArrayList<>();
        for (ProductSku sku : skuList) {
            if (sku != null && sku.getId() != null) {
                skuIds.add(sku.getId());
            }
        }
        evictLocalRetailSkuSnapshots(skuIds);
    }

    @Override
    public void evictBySkuId(Long skuId) {
        if (skuId == null) {
            return;
        }
        evictLocalRetailSkuSnapshots(List.of(skuId));
    }
}
