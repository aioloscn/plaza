package com.aiolos.plaza.product.service.facade.impl;

import com.aiolos.plaza.enums.ProductBizType;
import com.aiolos.plaza.mapper.ProductLadderPriceMapper;
import com.aiolos.plaza.mapper.ProductPublishRelMapper;
import com.aiolos.plaza.mapper.ProductSkuMapper;
import com.aiolos.plaza.mapper.ProductSkuSaleAttrMapper;
import com.aiolos.plaza.mapper.ProductSpuMapper;
import com.aiolos.plaza.mapper.ProductWeightRuleMapper;
import com.aiolos.plaza.model.po.ProductLadderPrice;
import com.aiolos.plaza.model.po.ProductPublishRel;
import com.aiolos.plaza.model.po.ProductSku;
import com.aiolos.plaza.model.po.ProductSkuSaleAttr;
import com.aiolos.plaza.model.po.ProductSpu;
import com.aiolos.plaza.model.po.ProductWeightRule;
import com.aiolos.plaza.product.model.dto.ProductCartSkuSnapshotDTO;
import com.aiolos.plaza.product.model.dto.ProductLadderPriceRuleDTO;
import com.aiolos.plaza.product.model.dto.ProductOrderSkuSnapshotDTO;
import com.aiolos.plaza.product.model.dto.ProductStorefrontSkuDTO;
import com.aiolos.plaza.product.model.dto.ProductWeightMetaDTO;
import com.aiolos.plaza.product.service.facade.ProductSnapshotFacade;
import com.aiolos.plaza.product.service.facade.ProductStorefrontFacade;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ProductSnapshotFacadeImpl implements ProductSnapshotFacade, ProductStorefrontFacade {

    private static final Integer ENABLED_STATUS = 1;

    private final ProductSkuMapper productSkuMapper;
    private final ProductSpuMapper productSpuMapper;
    private final ProductPublishRelMapper productPublishRelMapper;
    private final ProductSkuSaleAttrMapper productSkuSaleAttrMapper;
    private final ProductLadderPriceMapper productLadderPriceMapper;
    private final ProductWeightRuleMapper productWeightRuleMapper;
    private final ObjectMapper objectMapper;

    public ProductSnapshotFacadeImpl(ProductSkuMapper productSkuMapper,
                                     ProductSpuMapper productSpuMapper,
                                     ProductPublishRelMapper productPublishRelMapper,
                                     ProductSkuSaleAttrMapper productSkuSaleAttrMapper,
                                     ProductLadderPriceMapper productLadderPriceMapper,
                                     ProductWeightRuleMapper productWeightRuleMapper,
                                     ObjectMapper objectMapper) {
        this.productSkuMapper = productSkuMapper;
        this.productSpuMapper = productSpuMapper;
        this.productPublishRelMapper = productPublishRelMapper;
        this.productSkuSaleAttrMapper = productSkuSaleAttrMapper;
        this.productLadderPriceMapper = productLadderPriceMapper;
        this.productWeightRuleMapper = productWeightRuleMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProductCartSkuSnapshotDTO getCartSkuSnapshot(Long skuId, ProductBizType bizType) {
        return batchGetCartSkuSnapshots(Collections.singletonList(skuId), bizType).get(skuId);
    }

    @Override
    public Map<Long, ProductCartSkuSnapshotDTO> batchGetCartSkuSnapshots(List<Long> skuIds, ProductBizType bizType) {
        Map<Long, ProductPublishRel> publishRelMap = loadPublishRelMap(skuIds, bizType);
        if (publishRelMap.isEmpty()) {
            return Collections.emptyMap();
        }
        // 购物车轻量快照改为基于发布关系聚合，确保统一主表下仍能按业务场景过滤商品
        Map<Long, ProductSku> skuMap = loadSkuMap(publishRelMap.keySet());
        if (skuMap.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, ProductSpu> spuMap = loadSpuMap(skuMap.values());
        Map<Long, String> saleAttrJsonMap = loadSaleAttrJsonMap(skuMap.keySet());
        Map<Long, ProductWeightMetaDTO> weightMetaMap = loadWeightMetaMap(skuMap.keySet(), bizType);
        Map<Long, ProductCartSkuSnapshotDTO> result = new LinkedHashMap<>();
        for (Map.Entry<Long, ProductPublishRel> entry : publishRelMap.entrySet()) {
            ProductSku sku = skuMap.get(entry.getKey());
            if (sku == null) {
                continue;
            }
            ProductSpu spu = spuMap.get(sku.getSpuId());
            if (spu == null) {
                continue;
            }
            ProductCartSkuSnapshotDTO dto = new ProductCartSkuSnapshotDTO();
            fillBaseSnapshot(dto, sku, spu, entry.getValue(), saleAttrJsonMap.get(entry.getKey()), weightMetaMap.get(entry.getKey()));
            result.put(entry.getKey(), dto);
        }
        return result;
    }

    @Override
    public ProductOrderSkuSnapshotDTO getOrderSkuSnapshot(Long skuId, ProductBizType bizType) {
        return batchGetOrderSkuSnapshots(Collections.singletonList(skuId), bizType).get(skuId);
    }

    @Override
    public Map<Long, ProductOrderSkuSnapshotDTO> batchGetOrderSkuSnapshots(List<Long> skuIds, ProductBizType bizType) {
        Map<Long, ProductPublishRel> publishRelMap = loadPublishRelMap(skuIds, bizType);
        if (publishRelMap.isEmpty()) {
            return Collections.emptyMap();
        }
        // 下单快照同样以发布关系为入口，再补齐价格和计重规则
        Map<Long, ProductSku> skuMap = loadSkuMap(publishRelMap.keySet());
        if (skuMap.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, ProductSpu> spuMap = loadSpuMap(skuMap.values());
        Map<Long, String> saleAttrJsonMap = loadSaleAttrJsonMap(skuMap.keySet());
        Map<Long, ProductWeightMetaDTO> weightMetaMap = loadWeightMetaMap(skuMap.keySet(), bizType);
        Map<Long, List<ProductLadderPriceRuleDTO>> ladderPriceMap = loadLadderPriceMap(skuMap.keySet(), bizType);
        Map<Long, ProductOrderSkuSnapshotDTO> result = new LinkedHashMap<>();
        for (Map.Entry<Long, ProductPublishRel> entry : publishRelMap.entrySet()) {
            ProductSku sku = skuMap.get(entry.getKey());
            if (sku == null) {
                continue;
            }
            ProductSpu spu = spuMap.get(sku.getSpuId());
            if (spu == null) {
                continue;
            }
            ProductPublishRel publishRel = entry.getValue();
            ProductOrderSkuSnapshotDTO dto = new ProductOrderSkuSnapshotDTO();
            dto.setSpuId(spu.getId());
            dto.setSkuId(sku.getId());
            dto.setShopId(publishRel.getShopId());
            dto.setBizType(publishRel.getBizType());
            dto.setSpuName(spu.getSpuName());
            dto.setSkuName(sku.getSkuName());
            dto.setImageUrl(resolveImage(sku, spu));
            dto.setSaleAttrJson(saleAttrJsonMap.get(sku.getId()));
            dto.setMarketPrice(sku.getMarketPrice());
            dto.setSalePrice(resolveSalePrice(sku, publishRel));
            dto.setAvailableStock(sku.getAvailableStock());
            dto.setStatus(resolveSnapshotStatus(spu, sku, publishRel));
            dto.setWeightMeta(weightMetaMap.get(sku.getId()));
            dto.setLadderPriceRules(ladderPriceMap.getOrDefault(sku.getId(), Collections.emptyList()));
            result.put(entry.getKey(), dto);
        }
        return result;
    }

    @Override
    public List<ProductStorefrontSkuDTO> listShopSkuSnapshots(Long shopId, ProductBizType bizType) {
        if (shopId == null) {
            return Collections.emptyList();
        }
        List<ProductPublishRel> publishRelList = productPublishRelMapper.selectList(Wrappers.<ProductPublishRel>lambdaQuery()
                .eq(ProductPublishRel::getShopId, shopId)
                .eq(bizType != null, ProductPublishRel::getBizType, bizType == null ? null : bizType.getCode())
                .eq(ProductPublishRel::getSaleStatus, ENABLED_STATUS)
                .eq(ProductPublishRel::getVisibleStatus, ENABLED_STATUS)
                .orderByAsc(ProductPublishRel::getSortNo)
                .orderByAsc(ProductPublishRel::getId));
        return buildStorefrontSnapshotList(publishRelList);
    }

    @Override
    public ProductStorefrontSkuDTO getShopSkuSnapshot(Long skuId, ProductBizType bizType) {
        if (skuId == null) {
            return null;
        }
        List<ProductPublishRel> publishRelList = productPublishRelMapper.selectList(Wrappers.<ProductPublishRel>lambdaQuery()
                .eq(ProductPublishRel::getSkuId, skuId)
                .eq(bizType != null, ProductPublishRel::getBizType, bizType == null ? null : bizType.getCode())
                .eq(ProductPublishRel::getSaleStatus, ENABLED_STATUS)
                .eq(ProductPublishRel::getVisibleStatus, ENABLED_STATUS)
                .orderByAsc(ProductPublishRel::getSortNo)
                .orderByAsc(ProductPublishRel::getId));
        List<ProductStorefrontSkuDTO> snapshotList = buildStorefrontSnapshotList(publishRelList);
        return snapshotList.isEmpty() ? null : snapshotList.get(0);
    }

    private void fillBaseSnapshot(ProductCartSkuSnapshotDTO dto,
                                  ProductSku sku,
                                  ProductSpu spu,
                                  ProductPublishRel publishRel,
                                  String saleAttrJson,
                                  ProductWeightMetaDTO weightMeta) {
        dto.setSpuId(spu.getId());
        dto.setSkuId(sku.getId());
        dto.setShopId(publishRel.getShopId());
        dto.setBizType(publishRel.getBizType());
        dto.setSpuName(spu.getSpuName());
        dto.setSkuName(sku.getSkuName());
        dto.setImageUrl(resolveImage(sku, spu));
        dto.setSaleAttrJson(saleAttrJson);
        dto.setSalePrice(resolveSalePrice(sku, publishRel));
        dto.setAvailableStock(sku.getAvailableStock());
        dto.setStatus(resolveSnapshotStatus(spu, sku, publishRel));
        dto.setWeightMeta(weightMeta);
    }

    private List<ProductStorefrontSkuDTO> buildStorefrontSnapshotList(List<ProductPublishRel> publishRelList) {
        if (publishRelList == null || publishRelList.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> skuIds = new LinkedHashSet<>();
        for (ProductPublishRel publishRel : publishRelList) {
            if (publishRel != null && publishRel.getSkuId() != null) {
                skuIds.add(publishRel.getSkuId());
            }
        }
        if (skuIds.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, ProductSku> skuMap = loadSkuMap(skuIds);
        if (skuMap.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, ProductSpu> spuMap = loadSpuMap(skuMap.values());
        List<ProductStorefrontSkuDTO> result = new ArrayList<>();
        for (ProductPublishRel publishRel : publishRelList) {
            if (publishRel == null || publishRel.getSkuId() == null) {
                continue;
            }
            ProductSku sku = skuMap.get(publishRel.getSkuId());
            if (sku == null) {
                continue;
            }
            ProductSpu spu = spuMap.get(sku.getSpuId());
            if (spu == null || !Objects.equals(resolveSnapshotStatus(spu, sku, publishRel), ENABLED_STATUS)) {
                continue;
            }
            ProductStorefrontSkuDTO dto = new ProductStorefrontSkuDTO();
            dto.setSkuId(sku.getId());
            dto.setSpuId(spu.getId());
            dto.setShopId(publishRel.getShopId());
            dto.setBizType(publishRel.getBizType());
            dto.setName(sku.getSkuName());
            dto.setDescription(spu.getDescription());
            dto.setImageUrl(resolveImage(sku, spu));
            dto.setPrice(resolveSalePrice(sku, publishRel));
            dto.setStock(sku.getAvailableStock());
            dto.setStatus(ENABLED_STATUS);
            result.add(dto);
        }
        return result;
    }

    private Map<Long, ProductPublishRel> loadPublishRelMap(List<Long> skuIds, ProductBizType bizType) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> distinctIds = new LinkedHashSet<>();
        for (Long skuId : skuIds) {
            if (skuId != null) {
                distinctIds.add(skuId);
            }
        }
        if (distinctIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ProductPublishRel> publishRelList = productPublishRelMapper.selectList(Wrappers.<ProductPublishRel>lambdaQuery()
                .in(ProductPublishRel::getSkuId, distinctIds)
                .eq(bizType != null, ProductPublishRel::getBizType, bizType == null ? null : bizType.getCode())
                .orderByAsc(ProductPublishRel::getSkuId)
                .orderByAsc(ProductPublishRel::getSortNo));
        if (publishRelList == null || publishRelList.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, ProductPublishRel> result = new LinkedHashMap<>();
        for (ProductPublishRel publishRel : publishRelList) {
            if (publishRel != null && publishRel.getSkuId() != null) {
                result.putIfAbsent(publishRel.getSkuId(), publishRel);
            }
        }
        return result;
    }

    private Map<Long, ProductSku> loadSkuMap(Set<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ProductSku> skuList = productSkuMapper.selectList(Wrappers.<ProductSku>lambdaQuery()
                .in(ProductSku::getId, skuIds));
        if (skuList == null || skuList.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, ProductSku> result = new LinkedHashMap<>();
        for (ProductSku sku : skuList) {
            if (sku != null && sku.getId() != null) {
                result.put(sku.getId(), sku);
            }
        }
        return result;
    }

    private Map<Long, ProductSpu> loadSpuMap(Iterable<ProductSku> skuList) {
        Set<Long> spuIds = new LinkedHashSet<>();
        for (ProductSku sku : skuList) {
            if (sku != null && sku.getSpuId() != null) {
                spuIds.add(sku.getSpuId());
            }
        }
        if (spuIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ProductSpu> spuList = productSpuMapper.selectList(Wrappers.<ProductSpu>lambdaQuery()
                .in(ProductSpu::getId, spuIds));
        if (spuList == null || spuList.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, ProductSpu> result = new LinkedHashMap<>();
        for (ProductSpu spu : spuList) {
            if (spu != null && spu.getId() != null) {
                result.put(spu.getId(), spu);
            }
        }
        return result;
    }

    private Map<Long, String> loadSaleAttrJsonMap(Set<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ProductSkuSaleAttr> attrList = productSkuSaleAttrMapper.selectList(Wrappers.<ProductSkuSaleAttr>lambdaQuery()
                .in(ProductSkuSaleAttr::getSkuId, skuIds)
                .orderByAsc(ProductSkuSaleAttr::getSkuId)
                .orderByAsc(ProductSkuSaleAttr::getSaleAttrId)
                .orderByAsc(ProductSkuSaleAttr::getId));
        if (attrList == null || attrList.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, List<Map<String, String>>> groupedMap = new LinkedHashMap<>();
        for (ProductSkuSaleAttr attr : attrList) {
            if (attr == null || attr.getSkuId() == null) {
                continue;
            }
            Map<String, String> attrMap = new LinkedHashMap<>();
            attrMap.put("attrName", attr.getAttrName());
            attrMap.put("attrValue", attr.getAttrValue());
            groupedMap.computeIfAbsent(attr.getSkuId(), key -> new ArrayList<>()).add(attrMap);
        }
        Map<Long, String> result = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Map<String, String>>> entry : groupedMap.entrySet()) {
            result.put(entry.getKey(), toJson(entry.getValue()));
        }
        return result;
    }

    private Map<Long, ProductWeightMetaDTO> loadWeightMetaMap(Set<Long> skuIds, ProductBizType bizType) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ProductWeightRule> ruleList = productWeightRuleMapper.selectList(Wrappers.<ProductWeightRule>lambdaQuery()
                .in(ProductWeightRule::getSkuId, skuIds)
                .eq(bizType != null, ProductWeightRule::getBizType, bizType == null ? null : bizType.getCode())
                .eq(ProductWeightRule::getStatus, 1));
        if (ruleList == null || ruleList.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, ProductWeightMetaDTO> result = new LinkedHashMap<>();
        for (ProductWeightRule rule : ruleList) {
            if (rule == null || rule.getSkuId() == null || result.containsKey(rule.getSkuId())) {
                continue;
            }
            ProductWeightMetaDTO dto = new ProductWeightMetaDTO();
            dto.setPricingWeightType(rule.getPricingWeightType());
            dto.setWeightPrecision(rule.getWeightPrecision());
            dto.setMinWeight(rule.getMinWeight());
            dto.setMaxWeight(rule.getMaxWeight());
            dto.setStepWeight(rule.getStepWeight());
            dto.setRoundingMode(rule.getRoundingMode());
            dto.setExtConfigJson(rule.getExtConfigJson());
            result.put(rule.getSkuId(), dto);
        }
        return result;
    }

    private Map<Long, List<ProductLadderPriceRuleDTO>> loadLadderPriceMap(Set<Long> skuIds, ProductBizType bizType) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ProductLadderPrice> ruleList = productLadderPriceMapper.selectList(Wrappers.<ProductLadderPrice>lambdaQuery()
                .in(ProductLadderPrice::getSkuId, skuIds)
                .eq(bizType != null, ProductLadderPrice::getBizType, bizType == null ? null : bizType.getCode())
                .eq(ProductLadderPrice::getStatus, 1)
                .orderByAsc(ProductLadderPrice::getSkuId)
                .orderByAsc(ProductLadderPrice::getMinQuantity));
        if (ruleList == null || ruleList.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, List<ProductLadderPriceRuleDTO>> result = new LinkedHashMap<>();
        for (ProductLadderPrice rule : ruleList) {
            if (rule == null || rule.getSkuId() == null) {
                continue;
            }
            ProductLadderPriceRuleDTO dto = new ProductLadderPriceRuleDTO();
            dto.setMinQuantity(rule.getMinQuantity());
            dto.setMaxQuantity(rule.getMaxQuantity());
            dto.setLadderPrice(rule.getLadderPrice());
            result.computeIfAbsent(rule.getSkuId(), key -> new ArrayList<>()).add(dto);
        }
        return result;
    }

    private String resolveImage(ProductSku sku, ProductSpu spu) {
        if (sku != null && sku.getImageUrl() != null && !sku.getImageUrl().isBlank()) {
            return sku.getImageUrl();
        }
        return spu == null ? null : spu.getMainImage();
    }

    private Integer resolveSnapshotStatus(ProductSpu spu, ProductSku sku, ProductPublishRel publishRel) {
        return Objects.equals(spu == null ? null : spu.getStatus(), ENABLED_STATUS)
                && Objects.equals(sku == null ? null : sku.getStatus(), ENABLED_STATUS)
                && Objects.equals(publishRel == null ? null : publishRel.getSaleStatus(), ENABLED_STATUS)
                && Objects.equals(publishRel == null ? null : publishRel.getVisibleStatus(), ENABLED_STATUS)
                ? ENABLED_STATUS : 0;
    }

    private BigDecimal resolveSalePrice(ProductSku sku, ProductPublishRel publishRel) {
        if (publishRel != null && publishRel.getChannelSalePrice() != null) {
            return publishRel.getChannelSalePrice();
        }
        return sku == null ? null : sku.getSalePrice();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
