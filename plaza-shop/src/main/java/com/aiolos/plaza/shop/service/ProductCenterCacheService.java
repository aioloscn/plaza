package com.aiolos.plaza.shop.service;

import java.util.List;

public interface ProductCenterCacheService {

    void evictLocalRetailSkuSnapshots(List<Long> skuIds);

    void evictByPublishId(Long publishId);

    void evictBySpuId(Long spuId);

    void evictBySkuId(Long skuId);
}
