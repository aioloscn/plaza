package com.aiolos.plaza.product.service.app.impl;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.ProductBizType;
import com.aiolos.plaza.enums.exceptions.ProductExceptionEnum;
import com.aiolos.plaza.mapper.ProductEcommerceExtMapper;
import com.aiolos.plaza.mapper.ProductLadderPriceMapper;
import com.aiolos.plaza.mapper.ProductLocalExtMapper;
import com.aiolos.plaza.mapper.ProductMediaMapper;
import com.aiolos.plaza.mapper.ProductPublishRelMapper;
import com.aiolos.plaza.mapper.ProductSaleAttrMapper;
import com.aiolos.plaza.mapper.ProductSkuMapper;
import com.aiolos.plaza.mapper.ProductSkuSaleAttrMapper;
import com.aiolos.plaza.mapper.ProductSpuMapper;
import com.aiolos.plaza.mapper.ProductWeightRuleMapper;
import com.aiolos.plaza.model.po.ProductEcommerceExt;
import com.aiolos.plaza.model.po.ProductLadderPrice;
import com.aiolos.plaza.model.po.ProductLocalExt;
import com.aiolos.plaza.model.po.ProductMedia;
import com.aiolos.plaza.model.po.ProductPublishRel;
import com.aiolos.plaza.model.po.ProductSaleAttr;
import com.aiolos.plaza.model.po.ProductSku;
import com.aiolos.plaza.model.po.ProductSkuSaleAttr;
import com.aiolos.plaza.model.po.ProductSpu;
import com.aiolos.plaza.model.po.ProductWeightRule;
import com.aiolos.plaza.product.model.bo.ProductManagePageReq;
import com.aiolos.plaza.product.model.bo.ProductPublishUpdateReq;
import com.aiolos.plaza.product.model.bo.ProductSaveReq;
import com.aiolos.plaza.product.model.dto.ProductManageDetailDTO;
import com.aiolos.plaza.product.model.dto.ProductManagePageDTO;
import com.aiolos.plaza.product.model.dto.ProductSaveResultDTO;
import com.aiolos.plaza.product.service.app.ProductWriteAppService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Service
public class ProductWriteAppServiceImpl implements ProductWriteAppService {

    private static final Integer ENABLED_STATUS = 1;

    private final ProductSpuMapper productSpuMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ProductPublishRelMapper productPublishRelMapper;
    private final ProductLocalExtMapper productLocalExtMapper;
    private final ProductEcommerceExtMapper productEcommerceExtMapper;
    private final ProductMediaMapper productMediaMapper;
    private final ProductSaleAttrMapper productSaleAttrMapper;
    private final ProductSkuSaleAttrMapper productSkuSaleAttrMapper;
    private final ProductLadderPriceMapper productLadderPriceMapper;
    private final ProductWeightRuleMapper productWeightRuleMapper;

    public ProductWriteAppServiceImpl(ProductSpuMapper productSpuMapper,
                                      ProductSkuMapper productSkuMapper,
                                      ProductPublishRelMapper productPublishRelMapper,
                                      ProductLocalExtMapper productLocalExtMapper,
                                      ProductEcommerceExtMapper productEcommerceExtMapper,
                                      ProductMediaMapper productMediaMapper,
                                      ProductSaleAttrMapper productSaleAttrMapper,
                                      ProductSkuSaleAttrMapper productSkuSaleAttrMapper,
                                      ProductLadderPriceMapper productLadderPriceMapper,
                                      ProductWeightRuleMapper productWeightRuleMapper) {
        this.productSpuMapper = productSpuMapper;
        this.productSkuMapper = productSkuMapper;
        this.productPublishRelMapper = productPublishRelMapper;
        this.productLocalExtMapper = productLocalExtMapper;
        this.productEcommerceExtMapper = productEcommerceExtMapper;
        this.productMediaMapper = productMediaMapper;
        this.productSaleAttrMapper = productSaleAttrMapper;
        this.productSkuSaleAttrMapper = productSkuSaleAttrMapper;
        this.productLadderPriceMapper = productLadderPriceMapper;
        this.productWeightRuleMapper = productWeightRuleMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductSaveResultDTO createProduct(ProductSaveReq req) {
        validateBaseReq(req);
        LocalDateTime now = LocalDateTime.now();

        ProductSpu spu = buildSpu(req, now);
        productSpuMapper.insert(spu);

        saveLocalExt(spu.getId(), req.getLocalExt(), now);
        saveEcommerceExt(spu.getId(), req.getEcommerceExt(), now);
        saveMediaList(spu.getId(), null, req.getSpuMediaList(), now);

        Map<String, ProductSaleAttr> saleAttrMap = saveSpuSaleAttrs(spu.getId(), req.getSkuList(), now);
        List<FinalSkuRef> finalSkuRefs = new ArrayList<>();
        for (ProductSaveReq.SkuReq skuReq : req.getSkuList()) {
            ProductSku sku = buildSku(spu, skuReq, now);
            productSkuMapper.insert(sku);
            finalSkuRefs.add(new FinalSkuRef(skuReq, sku.getId()));
        }
        saveSkuChildren(spu.getId(), req.getShopId(), finalSkuRefs, saleAttrMap, now);
        return buildSaveResult(spu.getId(), finalSkuRefs);
    }

    @Override
    public ProductManageDetailDTO getProductDetail(Long spuId) {
        ProductSpu spu = loadSpu(spuId);
        ProductManageDetailDTO detailDTO = new ProductManageDetailDTO();
        detailDTO.setSpuId(spu.getId());
        detailDTO.setShopId(spu.getShopId());
        detailDTO.setSpuName(spu.getSpuName());
        detailDTO.setSpuCode(spu.getSpuCode());
        detailDTO.setCategoryId(spu.getCategoryId());
        detailDTO.setBrandId(spu.getBrandId());
        detailDTO.setMainImage(spu.getMainImage());
        detailDTO.setAlbumImages(spu.getAlbumImages());
        detailDTO.setProductType(spu.getProductType());
        detailDTO.setSourceType(spu.getSourceType());
        detailDTO.setStatus(spu.getStatus());
        detailDTO.setDescription(spu.getDescription());
        detailDTO.setExtConfigJson(spu.getExtConfigJson());

        ProductLocalExt localExt = productLocalExtMapper.selectOne(Wrappers.<ProductLocalExt>lambdaQuery()
                .eq(ProductLocalExt::getSpuId, spuId));
        if (localExt != null) {
            ProductManageDetailDTO.LocalExtDTO localExtDTO = new ProductManageDetailDTO.LocalExtDTO();
            localExtDTO.setPackingFee(localExt.getPackingFee());
            localExtDTO.setUnitName(localExt.getUnitName());
            localExtDTO.setMinPurchaseQty(localExt.getMinPurchaseQty());
            localExtDTO.setMaxPurchaseQty(localExt.getMaxPurchaseQty());
            localExtDTO.setSupportTakeaway(localExt.getSupportTakeaway());
            localExtDTO.setSupportSelfPickup(localExt.getSupportSelfPickup());
            localExtDTO.setSaleTimeJson(localExt.getSaleTimeJson());
            localExtDTO.setTagJson(localExt.getTagJson());
            localExtDTO.setExtConfigJson(localExt.getExtConfigJson());
            detailDTO.setLocalExt(localExtDTO);
        }

        ProductEcommerceExt ecommerceExt = productEcommerceExtMapper.selectOne(Wrappers.<ProductEcommerceExt>lambdaQuery()
                .eq(ProductEcommerceExt::getSpuId, spuId));
        if (ecommerceExt != null) {
            ProductManageDetailDTO.EcommerceExtDTO ecommerceExtDTO = new ProductManageDetailDTO.EcommerceExtDTO();
            ecommerceExtDTO.setLogisticsTemplateId(ecommerceExt.getLogisticsTemplateId());
            ecommerceExtDTO.setDeliveryOriginProvince(ecommerceExt.getDeliveryOriginProvince());
            ecommerceExtDTO.setDeliveryOriginCity(ecommerceExt.getDeliveryOriginCity());
            ecommerceExtDTO.setDeliveryOriginRegion(ecommerceExt.getDeliveryOriginRegion());
            ecommerceExtDTO.setDeliveryOriginDetail(ecommerceExt.getDeliveryOriginDetail());
            ecommerceExtDTO.setAfterSalePolicy(ecommerceExt.getAfterSalePolicy());
            ecommerceExtDTO.setDeliveryChannelJson(ecommerceExt.getDeliveryChannelJson());
            ecommerceExtDTO.setExtConfigJson(ecommerceExt.getExtConfigJson());
            detailDTO.setEcommerceExt(ecommerceExtDTO);
        }

        List<ProductSaleAttr> saleAttrOptions = productSaleAttrMapper.selectList(Wrappers.<ProductSaleAttr>lambdaQuery()
                .eq(ProductSaleAttr::getSpuId, spuId)
                .orderByAsc(ProductSaleAttr::getSortNo)
                .orderByAsc(ProductSaleAttr::getId));
        List<ProductManageDetailDTO.SaleAttrOptionDTO> saleAttrOptionDTOList = new ArrayList<>();
        for (ProductSaleAttr saleAttr : saleAttrOptions) {
            ProductManageDetailDTO.SaleAttrOptionDTO dto = new ProductManageDetailDTO.SaleAttrOptionDTO();
            dto.setSaleAttrId(saleAttr.getId());
            dto.setAttrName(saleAttr.getAttrName());
            dto.setAttrValue(saleAttr.getAttrValue());
            dto.setSortNo(saleAttr.getSortNo());
            dto.setStatus(saleAttr.getStatus());
            saleAttrOptionDTOList.add(dto);
        }
        detailDTO.setSaleAttrOptionList(saleAttrOptionDTOList);

        detailDTO.setSpuMediaList(toMediaDTOList(productMediaMapper.selectList(Wrappers.<ProductMedia>lambdaQuery()
                .eq(ProductMedia::getSpuId, spuId)
                .isNull(ProductMedia::getSkuId)
                .orderByAsc(ProductMedia::getSortNo)
                .orderByAsc(ProductMedia::getId))));

        List<ProductSku> skuList = productSkuMapper.selectList(Wrappers.<ProductSku>lambdaQuery()
                .eq(ProductSku::getSpuId, spuId)
                .orderByAsc(ProductSku::getId));
        if (CollectionUtils.isEmpty(skuList)) {
            detailDTO.setSkuList(Collections.emptyList());
            return detailDTO;
        }

        List<Long> skuIds = new ArrayList<>();
        for (ProductSku sku : skuList) {
            skuIds.add(sku.getId());
        }

        Map<Long, List<ProductSkuSaleAttr>> skuSaleAttrMap = groupSkuSaleAttrBySkuId(productSkuSaleAttrMapper.selectList(Wrappers.<ProductSkuSaleAttr>lambdaQuery()
                .in(ProductSkuSaleAttr::getSkuId, skuIds)
                .orderByAsc(ProductSkuSaleAttr::getSaleAttrId)
                .orderByAsc(ProductSkuSaleAttr::getId)));
        Map<Long, List<ProductPublishRel>> publishRelMap = groupPublishRelBySkuId(productPublishRelMapper.selectList(Wrappers.<ProductPublishRel>lambdaQuery()
                .in(ProductPublishRel::getSkuId, skuIds)
                .orderByAsc(ProductPublishRel::getSortNo)
                .orderByAsc(ProductPublishRel::getId)));
        Map<Long, List<ProductMedia>> mediaMap = groupMediaBySkuId(productMediaMapper.selectList(Wrappers.<ProductMedia>lambdaQuery()
                .in(ProductMedia::getSkuId, skuIds)
                .orderByAsc(ProductMedia::getSortNo)
                .orderByAsc(ProductMedia::getId)));
        Map<Long, List<ProductLadderPrice>> ladderPriceMap = groupLadderPriceBySkuId(productLadderPriceMapper.selectList(Wrappers.<ProductLadderPrice>lambdaQuery()
                .in(ProductLadderPrice::getSkuId, skuIds)
                .orderByAsc(ProductLadderPrice::getBizType)
                .orderByAsc(ProductLadderPrice::getMinQuantity)
                .orderByAsc(ProductLadderPrice::getId)));
        Map<Long, List<ProductWeightRule>> weightRuleMap = groupWeightRuleBySkuId(productWeightRuleMapper.selectList(Wrappers.<ProductWeightRule>lambdaQuery()
                .in(ProductWeightRule::getSkuId, skuIds)
                .orderByAsc(ProductWeightRule::getBizType)
                .orderByAsc(ProductWeightRule::getId)));

        List<ProductManageDetailDTO.SkuDTO> skuDTOList = new ArrayList<>();
        for (ProductSku sku : skuList) {
            ProductManageDetailDTO.SkuDTO skuDTO = new ProductManageDetailDTO.SkuDTO();
            skuDTO.setSkuId(sku.getId());
            skuDTO.setSkuCode(sku.getSkuCode());
            skuDTO.setSkuName(sku.getSkuName());
            skuDTO.setBarCode(sku.getBarCode());
            skuDTO.setMarketPrice(sku.getMarketPrice());
            skuDTO.setSalePrice(sku.getSalePrice());
            skuDTO.setCostPrice(sku.getCostPrice());
            skuDTO.setTotalStock(sku.getTotalStock());
            skuDTO.setAvailableStock(sku.getAvailableStock());
            skuDTO.setFrozenStock(sku.getFrozenStock());
            skuDTO.setStatus(sku.getStatus());
            skuDTO.setDefaultWeight(sku.getDefaultWeight());
            skuDTO.setWeightUnit(sku.getWeightUnit());
            skuDTO.setDefaultVolume(sku.getDefaultVolume());
            skuDTO.setVolumeUnit(sku.getVolumeUnit());
            skuDTO.setImageUrl(sku.getImageUrl());
            skuDTO.setExtConfigJson(sku.getExtConfigJson());
            skuDTO.setSaleAttrList(toSkuSaleAttrDTOList(skuSaleAttrMap.get(sku.getId())));
            skuDTO.setPublishList(toPublishDTOList(publishRelMap.get(sku.getId())));
            skuDTO.setMediaList(toMediaDTOList(mediaMap.get(sku.getId())));
            skuDTO.setLadderPriceList(toLadderPriceDTOList(ladderPriceMap.get(sku.getId())));
            skuDTO.setWeightRuleList(toWeightRuleDTOList(weightRuleMap.get(sku.getId())));
            skuDTOList.add(skuDTO);
        }
        detailDTO.setSkuList(skuDTOList);
        return detailDTO;
    }

    @Override
    public ProductManagePageDTO pageProducts(ProductManagePageReq req) {
        ProductManagePageReq pageReq = req == null ? new ProductManagePageReq() : req;
        long pageNum = pageReq.getPageNum() == null || pageReq.getPageNum() <= 0 ? 1L : pageReq.getPageNum();
        long pageSize = pageReq.getPageSize() == null || pageReq.getPageSize() <= 0 ? 10L : pageReq.getPageSize();
        Set<Long> matchedSpuIdSet = null;

        if (pageReq.getBizType() != null) {
            List<ProductPublishRel> matchedPublishRelList = productPublishRelMapper.selectList(Wrappers.<ProductPublishRel>lambdaQuery()
                    .eq(ProductPublishRel::getBizType, pageReq.getBizType()));
            if (CollectionUtils.isEmpty(matchedPublishRelList)) {
                ProductManagePageDTO emptyResult = new ProductManagePageDTO();
                emptyResult.setPageNum(pageNum);
                emptyResult.setPageSize(pageSize);
                emptyResult.setTotal(0L);
                emptyResult.setRecords(Collections.emptyList());
                return emptyResult;
            }
            matchedSpuIdSet = new LinkedHashSet<>();
            for (ProductPublishRel publishRel : matchedPublishRelList) {
                matchedSpuIdSet.add(publishRel.getSpuId());
            }
            if (matchedSpuIdSet.isEmpty()) {
                ProductManagePageDTO emptyResult = new ProductManagePageDTO();
                emptyResult.setPageNum(pageNum);
                emptyResult.setPageSize(pageSize);
                emptyResult.setTotal(0L);
                emptyResult.setRecords(Collections.emptyList());
                return emptyResult;
            }
        }

        Page<ProductSpu> page = productSpuMapper.selectPage(new Page<>(pageNum, pageSize), Wrappers.<ProductSpu>lambdaQuery()
                .in(matchedSpuIdSet != null, ProductSpu::getId, matchedSpuIdSet == null ? Collections.emptyList() : matchedSpuIdSet)
                .eq(pageReq.getShopId() != null, ProductSpu::getShopId, pageReq.getShopId())
                .eq(pageReq.getStatus() != null, ProductSpu::getStatus, pageReq.getStatus())
                .and(StringUtils.hasText(pageReq.getKeyword()), wrapper -> wrapper
                        .like(ProductSpu::getSpuName, pageReq.getKeyword().trim())
                        .or()
                        .like(ProductSpu::getSpuCode, pageReq.getKeyword().trim()))
                .orderByDesc(ProductSpu::getId));

        List<ProductSpu> spuList = page.getRecords();
        ProductManagePageDTO result = new ProductManagePageDTO();
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setTotal(page.getTotal());
        if (CollectionUtils.isEmpty(spuList)) {
            result.setRecords(Collections.emptyList());
            return result;
        }

        List<Long> spuIds = new ArrayList<>();
        for (ProductSpu spu : spuList) {
            spuIds.add(spu.getId());
        }

        List<ProductSku> skuList = productSkuMapper.selectList(Wrappers.<ProductSku>lambdaQuery()
                .in(ProductSku::getSpuId, spuIds));
        Map<Long, Integer> skuCountMap = new LinkedHashMap<>();
        List<Long> skuIds = new ArrayList<>();
        for (ProductSku sku : safeList(skuList)) {
            skuIds.add(sku.getId());
            skuCountMap.merge(sku.getSpuId(), 1, Integer::sum);
        }

        Map<Long, LinkedHashSet<Integer>> bizTypeMap = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(skuIds)) {
            List<ProductPublishRel> publishRelList = productPublishRelMapper.selectList(Wrappers.<ProductPublishRel>lambdaQuery()
                    .in(ProductPublishRel::getSkuId, skuIds)
                    .eq(pageReq.getBizType() != null, ProductPublishRel::getBizType, pageReq.getBizType()));
            for (ProductPublishRel publishRel : safeList(publishRelList)) {
                bizTypeMap.computeIfAbsent(publishRel.getSpuId(), key -> new LinkedHashSet<>()).add(publishRel.getBizType());
            }
        }

        List<ProductManagePageDTO.ProductManageItemDTO> records = new ArrayList<>();
        for (ProductSpu spu : spuList) {
            records.add(buildPageItem(spu, skuCountMap, bizTypeMap));
        }
        result.setRecords(records);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductSaveResultDTO updateProduct(ProductSaveReq req) {
        validateUpdateReq(req);
        ProductSpu existingSpu = loadSpu(req.getSpuId());
        if (!existingSpu.getShopId().equals(req.getShopId())) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_PARAM_INVALID);
        }

        LocalDateTime now = LocalDateTime.now();
        ProductSpu spu = buildSpu(req, now);
        spu.setId(existingSpu.getId());
        spu.setCreateTime(existingSpu.getCreateTime());
        productSpuMapper.updateById(spu);

        // 后台维护按“请求快照全量覆盖”处理从属结构，避免统一商品中心里维护多套复杂差异合并逻辑
        replaceSpuExt(spu.getId(), req, now);
        replaceSpuMedia(spu.getId(), req.getSpuMediaList(), now);

        List<ProductSku> existingSkuList = productSkuMapper.selectList(Wrappers.<ProductSku>lambdaQuery()
                .eq(ProductSku::getSpuId, spu.getId()));
        Map<Long, ProductSku> existingSkuMap = new LinkedHashMap<>();
        for (ProductSku existingSku : existingSkuList) {
            existingSkuMap.put(existingSku.getId(), existingSku);
        }

        Set<Long> retainedExistingSkuIds = new LinkedHashSet<>();
        List<FinalSkuRef> finalSkuRefs = new ArrayList<>();
        for (ProductSaveReq.SkuReq skuReq : req.getSkuList()) {
            ProductSku sku = buildSku(spu, skuReq, now);
            if (skuReq.getSkuId() != null) {
                ProductSku existingSku = existingSkuMap.get(skuReq.getSkuId());
                if (existingSku == null) {
                    ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_PARAM_INVALID);
                }
                sku.setId(existingSku.getId());
                sku.setCreateTime(existingSku.getCreateTime());
                productSkuMapper.updateById(sku);
                retainedExistingSkuIds.add(existingSku.getId());
            } else {
                productSkuMapper.insert(sku);
            }
            finalSkuRefs.add(new FinalSkuRef(skuReq, sku.getId()));
        }

        List<Long> removedSkuIds = new ArrayList<>();
        for (ProductSku existingSku : existingSkuList) {
            if (!retainedExistingSkuIds.contains(existingSku.getId())) {
                removedSkuIds.add(existingSku.getId());
            }
        }
        deleteSkuAggregateData(removedSkuIds);
        if (!removedSkuIds.isEmpty()) {
            productSkuMapper.delete(Wrappers.<ProductSku>lambdaQuery().in(ProductSku::getId, removedSkuIds));
        }

        List<Long> finalSkuIds = new ArrayList<>();
        for (FinalSkuRef finalSkuRef : finalSkuRefs) {
            finalSkuIds.add(finalSkuRef.skuId);
        }
        clearCurrentSkuChildren(finalSkuIds);

        productSaleAttrMapper.delete(Wrappers.<ProductSaleAttr>lambdaQuery().eq(ProductSaleAttr::getSpuId, spu.getId()));
        Map<String, ProductSaleAttr> saleAttrMap = saveSpuSaleAttrs(spu.getId(), req.getSkuList(), now);
        saveSkuChildren(spu.getId(), req.getShopId(), finalSkuRefs, saleAttrMap, now);
        return buildSaveResult(spu.getId(), finalSkuRefs);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePublish(ProductPublishUpdateReq req) {
        if (req == null || req.getPublishId() == null) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_PUBLISH_ID_REQUIRED);
        }
        ProductPublishRel publishRel = productPublishRelMapper.selectById(req.getPublishId());
        if (publishRel == null) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_PUBLISH_NOT_EXIST);
        }
        if (req.getChannelSalePrice() == null
                && req.getSaleStatus() == null
                && req.getVisibleStatus() == null
                && req.getSortNo() == null
                && !Boolean.TRUE.equals(req.getResetChannelSalePrice())) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_PARAM_INVALID);
        }
        if (Boolean.TRUE.equals(req.getResetChannelSalePrice())) {
            publishRel.setChannelSalePrice(null);
        } else if (req.getChannelSalePrice() != null) {
            publishRel.setChannelSalePrice(req.getChannelSalePrice());
        }
        if (req.getSaleStatus() != null) {
            publishRel.setSaleStatus(req.getSaleStatus());
        }
        if (req.getVisibleStatus() != null) {
            publishRel.setVisibleStatus(req.getVisibleStatus());
        }
        if (req.getSortNo() != null) {
            publishRel.setSortNo(req.getSortNo());
        }
        publishRel.setUpdateTime(LocalDateTime.now());
        productPublishRelMapper.updateById(publishRel);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProduct(Long spuId) {
        ProductSpu spu = loadSpu(spuId);
        List<ProductSku> skuList = productSkuMapper.selectList(Wrappers.<ProductSku>lambdaQuery()
                .eq(ProductSku::getSpuId, spu.getId()));
        List<Long> skuIds = new ArrayList<>();
        for (ProductSku sku : safeList(skuList)) {
            skuIds.add(sku.getId());
        }
        deleteSkuAggregateData(skuIds);
        productMediaMapper.delete(Wrappers.<ProductMedia>lambdaQuery()
                .eq(ProductMedia::getSpuId, spuId)
                .isNull(ProductMedia::getSkuId));
        productSaleAttrMapper.delete(Wrappers.<ProductSaleAttr>lambdaQuery().eq(ProductSaleAttr::getSpuId, spuId));
        productLocalExtMapper.delete(Wrappers.<ProductLocalExt>lambdaQuery().eq(ProductLocalExt::getSpuId, spuId));
        productEcommerceExtMapper.delete(Wrappers.<ProductEcommerceExt>lambdaQuery().eq(ProductEcommerceExt::getSpuId, spuId));
        if (!CollectionUtils.isEmpty(skuIds)) {
            productSkuMapper.delete(Wrappers.<ProductSku>lambdaQuery().in(ProductSku::getId, skuIds));
        }
        productSpuMapper.deleteById(spuId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSku(Long skuId) {
        if (skuId == null) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_SKU_NOT_EXIST);
        }
        ProductSku sku = productSkuMapper.selectById(skuId);
        if (sku == null) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_SKU_NOT_EXIST);
        }
        List<ProductSku> siblingSkuList = productSkuMapper.selectList(Wrappers.<ProductSku>lambdaQuery()
                .eq(ProductSku::getSpuId, sku.getSpuId()));
        if (siblingSkuList == null || siblingSkuList.size() <= 1) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_LAST_SKU_DELETE_FORBIDDEN);
        }
        deleteSkuAggregateData(Collections.singletonList(skuId));
        productSkuMapper.deleteById(skuId);
    }

    private void validateBaseReq(ProductSaveReq req) {
        if (req == null) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_PARAM_INVALID);
        }
        if (req.getShopId() == null) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_SHOP_ID_REQUIRED);
        }
        if (!StringUtils.hasText(req.getSpuName())) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_SPU_NAME_REQUIRED);
        }
        if (CollectionUtils.isEmpty(req.getSkuList())) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_SKU_LIST_EMPTY);
        }
        for (ProductSaveReq.SkuReq skuReq : req.getSkuList()) {
            validateSkuReq(skuReq);
        }
    }

    private void validateUpdateReq(ProductSaveReq req) {
        validateBaseReq(req);
        if (req.getSpuId() == null) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_SPU_ID_REQUIRED);
        }
    }

    private void validateSkuReq(ProductSaveReq.SkuReq skuReq) {
        if (skuReq == null) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_PARAM_INVALID);
        }
        if (!StringUtils.hasText(skuReq.getSkuName())) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_SKU_NAME_REQUIRED);
        }
        if (skuReq.getSalePrice() == null) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_SKU_SALE_PRICE_REQUIRED);
        }
        resolveStockState(skuReq);
        validateSaleAttrList(skuReq.getSaleAttrList());
        validatePublishList(skuReq.getPublishList());
        validateLadderPriceList(skuReq.getLadderPriceList());
        validateWeightRuleList(skuReq.getWeightRuleList());
    }

    private void validateSaleAttrList(List<ProductSaveReq.SkuSaleAttrReq> saleAttrList) {
        if (CollectionUtils.isEmpty(saleAttrList)) {
            return;
        }
        for (ProductSaveReq.SkuSaleAttrReq saleAttrReq : saleAttrList) {
            if (saleAttrReq == null
                    || !StringUtils.hasText(saleAttrReq.getAttrName())
                    || !StringUtils.hasText(saleAttrReq.getAttrValue())) {
                ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_SALE_ATTR_INVALID);
            }
        }
    }

    private void validatePublishList(List<ProductSaveReq.PublishReq> publishList) {
        if (CollectionUtils.isEmpty(publishList)) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_PUBLISH_LIST_EMPTY);
        }
        Set<Integer> bizTypeSet = new LinkedHashSet<>();
        for (ProductSaveReq.PublishReq publishReq : publishList) {
            if (publishReq == null || ProductBizType.fromCode(publishReq.getBizType()) == null) {
                ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_BIZ_TYPE_INVALID);
            }
            if (!bizTypeSet.add(publishReq.getBizType())) {
                ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_PARAM_INVALID);
            }
        }
    }

    private void validateLadderPriceList(List<ProductSaveReq.LadderPriceReq> ladderPriceList) {
        if (CollectionUtils.isEmpty(ladderPriceList)) {
            return;
        }
        for (ProductSaveReq.LadderPriceReq ladderPriceReq : ladderPriceList) {
            if (ladderPriceReq == null
                    || ProductBizType.fromCode(ladderPriceReq.getBizType()) == null
                    || ladderPriceReq.getMinQuantity() == null
                    || ladderPriceReq.getLadderPrice() == null) {
                ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_PARAM_INVALID);
            }
        }
    }

    private void validateWeightRuleList(List<ProductSaveReq.WeightRuleReq> weightRuleList) {
        if (CollectionUtils.isEmpty(weightRuleList)) {
            return;
        }
        Set<Integer> bizTypeSet = new LinkedHashSet<>();
        for (ProductSaveReq.WeightRuleReq weightRuleReq : weightRuleList) {
            if (weightRuleReq == null
                    || ProductBizType.fromCode(weightRuleReq.getBizType()) == null
                    || weightRuleReq.getPricingWeightType() == null) {
                ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_PARAM_INVALID);
            }
            if (!bizTypeSet.add(weightRuleReq.getBizType())) {
                ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_PARAM_INVALID);
            }
        }
    }

    private ProductSpu loadSpu(Long spuId) {
        if (spuId == null) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_SPU_ID_REQUIRED);
        }
        ProductSpu spu = productSpuMapper.selectById(spuId);
        if (spu == null) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_NOT_EXIST);
        }
        return spu;
    }

    private ProductSpu buildSpu(ProductSaveReq req, LocalDateTime now) {
        ProductSpu spu = new ProductSpu();
        spu.setShopId(req.getShopId());
        spu.setSpuName(trimToNull(req.getSpuName()));
        spu.setSpuCode(trimToNull(req.getSpuCode()));
        spu.setCategoryId(req.getCategoryId());
        spu.setBrandId(req.getBrandId());
        spu.setMainImage(trimToNull(req.getMainImage()));
        spu.setAlbumImages(req.getAlbumImages());
        spu.setProductType(defaultIfNull(req.getProductType(), 1));
        spu.setSourceType(defaultIfNull(req.getSourceType(), 1));
        spu.setStatus(defaultIfNull(req.getStatus(), ENABLED_STATUS));
        spu.setDescription(req.getDescription());
        spu.setExtConfigJson(req.getExtConfigJson());
        spu.setCreateTime(now);
        spu.setUpdateTime(now);
        return spu;
    }

    private ProductSku buildSku(ProductSpu spu, ProductSaveReq.SkuReq skuReq, LocalDateTime now) {
        StockState stockState = resolveStockState(skuReq);
        ProductSku sku = new ProductSku();
        sku.setSpuId(spu.getId());
        sku.setShopId(spu.getShopId());
        sku.setSkuCode(trimToNull(skuReq.getSkuCode()));
        sku.setSkuName(trimToNull(skuReq.getSkuName()));
        sku.setBarCode(trimToNull(skuReq.getBarCode()));
        sku.setMarketPrice(skuReq.getMarketPrice());
        sku.setSalePrice(skuReq.getSalePrice());
        sku.setCostPrice(skuReq.getCostPrice());
        sku.setTotalStock(stockState.totalStock);
        sku.setAvailableStock(stockState.availableStock);
        sku.setFrozenStock(stockState.frozenStock);
        sku.setStatus(defaultIfNull(skuReq.getStatus(), ENABLED_STATUS));
        sku.setDefaultWeight(skuReq.getDefaultWeight());
        sku.setWeightUnit(trimToNull(skuReq.getWeightUnit()));
        sku.setDefaultVolume(skuReq.getDefaultVolume());
        sku.setVolumeUnit(trimToNull(skuReq.getVolumeUnit()));
        sku.setImageUrl(StringUtils.hasText(skuReq.getImageUrl()) ? skuReq.getImageUrl().trim() : trimToNull(spu.getMainImage()));
        sku.setExtConfigJson(skuReq.getExtConfigJson());
        sku.setCreateTime(now);
        sku.setUpdateTime(now);
        return sku;
    }

    private void replaceSpuExt(Long spuId, ProductSaveReq req, LocalDateTime now) {
        productLocalExtMapper.delete(Wrappers.<ProductLocalExt>lambdaQuery().eq(ProductLocalExt::getSpuId, spuId));
        productEcommerceExtMapper.delete(Wrappers.<ProductEcommerceExt>lambdaQuery().eq(ProductEcommerceExt::getSpuId, spuId));
        saveLocalExt(spuId, req.getLocalExt(), now);
        saveEcommerceExt(spuId, req.getEcommerceExt(), now);
    }

    private void replaceSpuMedia(Long spuId, List<ProductSaveReq.MediaReq> spuMediaList, LocalDateTime now) {
        productMediaMapper.delete(Wrappers.<ProductMedia>lambdaQuery()
                .eq(ProductMedia::getSpuId, spuId)
                .isNull(ProductMedia::getSkuId));
        saveMediaList(spuId, null, spuMediaList, now);
    }

    private void saveLocalExt(Long spuId, ProductSaveReq.LocalExtReq localExtReq, LocalDateTime now) {
        if (localExtReq == null) {
            return;
        }
        ProductLocalExt localExt = new ProductLocalExt();
        localExt.setSpuId(spuId);
        localExt.setPackingFee(localExtReq.getPackingFee());
        localExt.setUnitName(trimToNull(localExtReq.getUnitName()));
        localExt.setMinPurchaseQty(localExtReq.getMinPurchaseQty());
        localExt.setMaxPurchaseQty(localExtReq.getMaxPurchaseQty());
        localExt.setSupportTakeaway(localExtReq.getSupportTakeaway() == null ? Boolean.TRUE : localExtReq.getSupportTakeaway());
        localExt.setSupportSelfPickup(localExtReq.getSupportSelfPickup() == null ? Boolean.FALSE : localExtReq.getSupportSelfPickup());
        localExt.setSaleTimeJson(localExtReq.getSaleTimeJson());
        localExt.setTagJson(localExtReq.getTagJson());
        localExt.setExtConfigJson(localExtReq.getExtConfigJson());
        localExt.setCreateTime(now);
        localExt.setUpdateTime(now);
        productLocalExtMapper.insert(localExt);
    }

    private void saveEcommerceExt(Long spuId, ProductSaveReq.EcommerceExtReq ecommerceExtReq, LocalDateTime now) {
        if (ecommerceExtReq == null) {
            return;
        }
        ProductEcommerceExt ecommerceExt = new ProductEcommerceExt();
        ecommerceExt.setSpuId(spuId);
        ecommerceExt.setLogisticsTemplateId(ecommerceExtReq.getLogisticsTemplateId());
        ecommerceExt.setDeliveryOriginProvince(trimToNull(ecommerceExtReq.getDeliveryOriginProvince()));
        ecommerceExt.setDeliveryOriginCity(trimToNull(ecommerceExtReq.getDeliveryOriginCity()));
        ecommerceExt.setDeliveryOriginRegion(trimToNull(ecommerceExtReq.getDeliveryOriginRegion()));
        ecommerceExt.setDeliveryOriginDetail(trimToNull(ecommerceExtReq.getDeliveryOriginDetail()));
        ecommerceExt.setAfterSalePolicy(ecommerceExtReq.getAfterSalePolicy());
        ecommerceExt.setDeliveryChannelJson(ecommerceExtReq.getDeliveryChannelJson());
        ecommerceExt.setExtConfigJson(ecommerceExtReq.getExtConfigJson());
        ecommerceExt.setCreateTime(now);
        ecommerceExt.setUpdateTime(now);
        productEcommerceExtMapper.insert(ecommerceExt);
    }

    private Map<String, ProductSaleAttr> saveSpuSaleAttrs(Long spuId, List<ProductSaveReq.SkuReq> skuList, LocalDateTime now) {
        Map<String, ProductSaveReq.SkuSaleAttrReq> distinctAttrMap = new LinkedHashMap<>();
        for (ProductSaveReq.SkuReq skuReq : safeList(skuList)) {
            for (ProductSaveReq.SkuSaleAttrReq saleAttrReq : safeList(skuReq.getSaleAttrList())) {
                String key = buildSaleAttrKey(saleAttrReq.getAttrName(), saleAttrReq.getAttrValue());
                distinctAttrMap.putIfAbsent(key, saleAttrReq);
            }
        }
        if (distinctAttrMap.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, ProductSaleAttr> result = new LinkedHashMap<>();
        int sortNo = 0;
        for (Map.Entry<String, ProductSaveReq.SkuSaleAttrReq> entry : distinctAttrMap.entrySet()) {
            ProductSaleAttr saleAttr = new ProductSaleAttr();
            saleAttr.setSpuId(spuId);
            saleAttr.setAttrName(trimToNull(entry.getValue().getAttrName()));
            saleAttr.setAttrValue(trimToNull(entry.getValue().getAttrValue()));
            saleAttr.setSortNo(sortNo++);
            saleAttr.setStatus(ENABLED_STATUS);
            saleAttr.setCreateTime(now);
            saleAttr.setUpdateTime(now);
            productSaleAttrMapper.insert(saleAttr);
            result.put(entry.getKey(), saleAttr);
        }
        return result;
    }

    private void saveSkuChildren(Long spuId,
                                 Long shopId,
                                 List<FinalSkuRef> finalSkuRefs,
                                 Map<String, ProductSaleAttr> saleAttrMap,
                                 LocalDateTime now) {
        for (FinalSkuRef finalSkuRef : finalSkuRefs) {
            saveSkuSaleAttrs(finalSkuRef.skuId, saleAttrMap, finalSkuRef.skuReq.getSaleAttrList(), now);
            savePublishList(spuId, finalSkuRef.skuId, shopId, finalSkuRef.skuReq.getPublishList(), now);
            saveMediaList(spuId, finalSkuRef.skuId, finalSkuRef.skuReq.getMediaList(), now);
            saveLadderPrices(finalSkuRef.skuId, finalSkuRef.skuReq.getLadderPriceList(), now);
            saveWeightRules(finalSkuRef.skuId, finalSkuRef.skuReq.getWeightRuleList(), now);
        }
    }

    private ProductSaveResultDTO buildSaveResult(Long spuId, List<FinalSkuRef> finalSkuRefs) {
        ProductSaveResultDTO result = new ProductSaveResultDTO();
        List<Long> skuIds = new ArrayList<>();
        for (FinalSkuRef finalSkuRef : finalSkuRefs) {
            skuIds.add(finalSkuRef.skuId);
        }
        result.setSpuId(spuId);
        result.setSkuIds(skuIds);
        return result;
    }

    private void saveSkuSaleAttrs(Long skuId,
                                  Map<String, ProductSaleAttr> saleAttrMap,
                                  List<ProductSaveReq.SkuSaleAttrReq> saleAttrList,
                                  LocalDateTime now) {
        if (CollectionUtils.isEmpty(saleAttrList)) {
            return;
        }
        Set<Long> insertedSaleAttrIds = new LinkedHashSet<>();
        for (ProductSaveReq.SkuSaleAttrReq saleAttrReq : saleAttrList) {
            String key = buildSaleAttrKey(saleAttrReq.getAttrName(), saleAttrReq.getAttrValue());
            ProductSaleAttr saleAttr = saleAttrMap.get(key);
            if (saleAttr == null || saleAttr.getId() == null || !insertedSaleAttrIds.add(saleAttr.getId())) {
                continue;
            }
            ProductSkuSaleAttr skuSaleAttr = new ProductSkuSaleAttr();
            skuSaleAttr.setSkuId(skuId);
            skuSaleAttr.setSaleAttrId(saleAttr.getId());
            skuSaleAttr.setAttrName(saleAttr.getAttrName());
            skuSaleAttr.setAttrValue(saleAttr.getAttrValue());
            skuSaleAttr.setCreateTime(now);
            skuSaleAttr.setUpdateTime(now);
            productSkuSaleAttrMapper.insert(skuSaleAttr);
        }
    }

    private void savePublishList(Long spuId,
                                 Long skuId,
                                 Long shopId,
                                 List<ProductSaveReq.PublishReq> publishList,
                                 LocalDateTime now) {
        for (ProductSaveReq.PublishReq publishReq : safeList(publishList)) {
            ProductPublishRel publishRel = new ProductPublishRel();
            publishRel.setShopId(shopId);
            publishRel.setSpuId(spuId);
            publishRel.setSkuId(skuId);
            publishRel.setBizType(publishReq.getBizType());
            publishRel.setChannelSalePrice(publishReq.getChannelSalePrice());
            publishRel.setSaleStatus(defaultIfNull(publishReq.getSaleStatus(), ENABLED_STATUS));
            publishRel.setVisibleStatus(defaultIfNull(publishReq.getVisibleStatus(), ENABLED_STATUS));
            publishRel.setSortNo(defaultIfNull(publishReq.getSortNo(), 0));
            publishRel.setCreateTime(now);
            publishRel.setUpdateTime(now);
            productPublishRelMapper.insert(publishRel);
        }
    }

    private void saveMediaList(Long spuId, Long skuId, List<ProductSaveReq.MediaReq> mediaList, LocalDateTime now) {
        if (CollectionUtils.isEmpty(mediaList)) {
            return;
        }
        for (ProductSaveReq.MediaReq mediaReq : mediaList) {
            if (mediaReq == null || !StringUtils.hasText(mediaReq.getMediaUrl())) {
                continue;
            }
            ProductMedia media = new ProductMedia();
            media.setSpuId(spuId);
            media.setSkuId(skuId);
            media.setMediaType(defaultIfNull(mediaReq.getMediaType(), 1));
            media.setMediaUrl(mediaReq.getMediaUrl().trim());
            media.setSortNo(defaultIfNull(mediaReq.getSortNo(), 0));
            media.setStatus(defaultIfNull(mediaReq.getStatus(), ENABLED_STATUS));
            media.setCreateTime(now);
            media.setUpdateTime(now);
            productMediaMapper.insert(media);
        }
    }

    private void saveLadderPrices(Long skuId, List<ProductSaveReq.LadderPriceReq> ladderPriceList, LocalDateTime now) {
        for (ProductSaveReq.LadderPriceReq ladderPriceReq : safeList(ladderPriceList)) {
            ProductLadderPrice ladderPrice = new ProductLadderPrice();
            ladderPrice.setSkuId(skuId);
            ladderPrice.setBizType(ladderPriceReq.getBizType());
            ladderPrice.setMinQuantity(ladderPriceReq.getMinQuantity());
            ladderPrice.setMaxQuantity(ladderPriceReq.getMaxQuantity());
            ladderPrice.setLadderPrice(ladderPriceReq.getLadderPrice());
            ladderPrice.setStatus(defaultIfNull(ladderPriceReq.getStatus(), ENABLED_STATUS));
            ladderPrice.setCreateTime(now);
            ladderPrice.setUpdateTime(now);
            productLadderPriceMapper.insert(ladderPrice);
        }
    }

    private void saveWeightRules(Long skuId, List<ProductSaveReq.WeightRuleReq> weightRuleList, LocalDateTime now) {
        for (ProductSaveReq.WeightRuleReq weightRuleReq : safeList(weightRuleList)) {
            ProductWeightRule weightRule = new ProductWeightRule();
            weightRule.setSkuId(skuId);
            weightRule.setBizType(weightRuleReq.getBizType());
            weightRule.setPricingWeightType(weightRuleReq.getPricingWeightType());
            weightRule.setWeightPrecision(defaultIfNull(weightRuleReq.getWeightPrecision(), 0));
            weightRule.setMinWeight(weightRuleReq.getMinWeight());
            weightRule.setMaxWeight(weightRuleReq.getMaxWeight());
            weightRule.setStepWeight(weightRuleReq.getStepWeight());
            weightRule.setRoundingMode(defaultIfNull(weightRuleReq.getRoundingMode(), 1));
            weightRule.setExtConfigJson(weightRuleReq.getExtConfigJson());
            weightRule.setStatus(defaultIfNull(weightRuleReq.getStatus(), ENABLED_STATUS));
            weightRule.setCreateTime(now);
            weightRule.setUpdateTime(now);
            productWeightRuleMapper.insert(weightRule);
        }
    }

    private void clearCurrentSkuChildren(List<Long> skuIds) {
        if (CollectionUtils.isEmpty(skuIds)) {
            return;
        }
        productPublishRelMapper.delete(Wrappers.<ProductPublishRel>lambdaQuery().in(ProductPublishRel::getSkuId, skuIds));
        productSkuSaleAttrMapper.delete(Wrappers.<ProductSkuSaleAttr>lambdaQuery().in(ProductSkuSaleAttr::getSkuId, skuIds));
        productMediaMapper.delete(Wrappers.<ProductMedia>lambdaQuery().in(ProductMedia::getSkuId, skuIds));
        productLadderPriceMapper.delete(Wrappers.<ProductLadderPrice>lambdaQuery().in(ProductLadderPrice::getSkuId, skuIds));
        productWeightRuleMapper.delete(Wrappers.<ProductWeightRule>lambdaQuery().in(ProductWeightRule::getSkuId, skuIds));
    }

    private void deleteSkuAggregateData(List<Long> skuIds) {
        if (CollectionUtils.isEmpty(skuIds)) {
            return;
        }
        clearCurrentSkuChildren(skuIds);
    }

    private ProductManagePageDTO.ProductManageItemDTO buildPageItem(ProductSpu spu,
                                                                    Map<Long, Integer> skuCountMap,
                                                                    Map<Long, LinkedHashSet<Integer>> bizTypeMap) {
        ProductManagePageDTO.ProductManageItemDTO itemDTO = new ProductManagePageDTO.ProductManageItemDTO();
        itemDTO.setSpuId(spu.getId());
        itemDTO.setShopId(spu.getShopId());
        itemDTO.setSpuName(spu.getSpuName());
        itemDTO.setSpuCode(spu.getSpuCode());
        itemDTO.setMainImage(spu.getMainImage());
        itemDTO.setProductType(spu.getProductType());
        itemDTO.setStatus(spu.getStatus());
        itemDTO.setSkuCount(skuCountMap.getOrDefault(spu.getId(), 0));
        LinkedHashSet<Integer> bizTypeSet = bizTypeMap.getOrDefault(spu.getId(), new LinkedHashSet<>());
        List<Integer> bizTypeList = new ArrayList<>(bizTypeSet);
        List<String> bizTypeDescList = new ArrayList<>();
        for (Integer bizType : bizTypeList) {
            ProductBizType productBizType = ProductBizType.fromCode(bizType);
            if (productBizType != null) {
                bizTypeDescList.add(productBizType.getDesc());
            }
        }
        itemDTO.setBizTypeList(bizTypeList);
        itemDTO.setBizTypeDescList(bizTypeDescList);
        return itemDTO;
    }

    private StockState resolveStockState(ProductSaveReq.SkuReq skuReq) {
        int frozenStock = defaultIfNull(skuReq.getFrozenStock(), 0);
        Integer totalStockReq = skuReq.getTotalStock();
        Integer availableStockReq = skuReq.getAvailableStock();

        int totalStock;
        int availableStock;
        if (totalStockReq == null && availableStockReq == null) {
            totalStock = 0;
            availableStock = 0;
        } else if (totalStockReq == null) {
            availableStock = availableStockReq;
            totalStock = availableStock + frozenStock;
        } else if (availableStockReq == null) {
            totalStock = totalStockReq;
            availableStock = totalStock - frozenStock;
        } else {
            totalStock = totalStockReq;
            availableStock = availableStockReq;
        }

        if (totalStock < 0 || availableStock < 0 || frozenStock < 0 || availableStock + frozenStock > totalStock) {
            ExceptionUtil.throwException(ProductExceptionEnum.PRODUCT_SKU_STOCK_INVALID);
        }
        return new StockState(totalStock, availableStock, frozenStock);
    }

    private Map<Long, List<ProductSkuSaleAttr>> groupSkuSaleAttrBySkuId(List<ProductSkuSaleAttr> list) {
        Map<Long, List<ProductSkuSaleAttr>> result = new LinkedHashMap<>();
        for (ProductSkuSaleAttr item : safeList(list)) {
            if (item.getSkuId() != null) {
                result.computeIfAbsent(item.getSkuId(), key -> new ArrayList<>()).add(item);
            }
        }
        return result;
    }

    private Map<Long, List<ProductPublishRel>> groupPublishRelBySkuId(List<ProductPublishRel> list) {
        Map<Long, List<ProductPublishRel>> result = new LinkedHashMap<>();
        for (ProductPublishRel item : safeList(list)) {
            if (item.getSkuId() != null) {
                result.computeIfAbsent(item.getSkuId(), key -> new ArrayList<>()).add(item);
            }
        }
        return result;
    }

    private Map<Long, List<ProductMedia>> groupMediaBySkuId(List<ProductMedia> list) {
        Map<Long, List<ProductMedia>> result = new LinkedHashMap<>();
        for (ProductMedia item : safeList(list)) {
            if (item.getSkuId() != null) {
                result.computeIfAbsent(item.getSkuId(), key -> new ArrayList<>()).add(item);
            }
        }
        return result;
    }

    private Map<Long, List<ProductLadderPrice>> groupLadderPriceBySkuId(List<ProductLadderPrice> list) {
        Map<Long, List<ProductLadderPrice>> result = new LinkedHashMap<>();
        for (ProductLadderPrice item : safeList(list)) {
            if (item.getSkuId() != null) {
                result.computeIfAbsent(item.getSkuId(), key -> new ArrayList<>()).add(item);
            }
        }
        return result;
    }

    private Map<Long, List<ProductWeightRule>> groupWeightRuleBySkuId(List<ProductWeightRule> list) {
        Map<Long, List<ProductWeightRule>> result = new LinkedHashMap<>();
        for (ProductWeightRule item : safeList(list)) {
            if (item.getSkuId() != null) {
                result.computeIfAbsent(item.getSkuId(), key -> new ArrayList<>()).add(item);
            }
        }
        return result;
    }

    private List<ProductManageDetailDTO.MediaDTO> toMediaDTOList(List<ProductMedia> mediaList) {
        if (CollectionUtils.isEmpty(mediaList)) {
            return Collections.emptyList();
        }
        List<ProductManageDetailDTO.MediaDTO> result = new ArrayList<>();
        for (ProductMedia media : mediaList) {
            ProductManageDetailDTO.MediaDTO dto = new ProductManageDetailDTO.MediaDTO();
            dto.setMediaId(media.getId());
            dto.setMediaType(media.getMediaType());
            dto.setMediaUrl(media.getMediaUrl());
            dto.setSortNo(media.getSortNo());
            dto.setStatus(media.getStatus());
            result.add(dto);
        }
        return result;
    }

    private List<ProductManageDetailDTO.SkuSaleAttrDTO> toSkuSaleAttrDTOList(List<ProductSkuSaleAttr> saleAttrList) {
        if (CollectionUtils.isEmpty(saleAttrList)) {
            return Collections.emptyList();
        }
        List<ProductManageDetailDTO.SkuSaleAttrDTO> result = new ArrayList<>();
        for (ProductSkuSaleAttr saleAttr : saleAttrList) {
            ProductManageDetailDTO.SkuSaleAttrDTO dto = new ProductManageDetailDTO.SkuSaleAttrDTO();
            dto.setSaleAttrId(saleAttr.getSaleAttrId());
            dto.setAttrName(saleAttr.getAttrName());
            dto.setAttrValue(saleAttr.getAttrValue());
            result.add(dto);
        }
        return result;
    }

    private List<ProductManageDetailDTO.PublishDTO> toPublishDTOList(List<ProductPublishRel> publishList) {
        if (CollectionUtils.isEmpty(publishList)) {
            return Collections.emptyList();
        }
        List<ProductManageDetailDTO.PublishDTO> result = new ArrayList<>();
        for (ProductPublishRel publishRel : publishList) {
            ProductManageDetailDTO.PublishDTO dto = new ProductManageDetailDTO.PublishDTO();
            dto.setPublishId(publishRel.getId());
            dto.setBizType(publishRel.getBizType());
            dto.setChannelSalePrice(publishRel.getChannelSalePrice());
            dto.setSaleStatus(publishRel.getSaleStatus());
            dto.setVisibleStatus(publishRel.getVisibleStatus());
            dto.setSortNo(publishRel.getSortNo());
            result.add(dto);
        }
        return result;
    }

    private List<ProductManageDetailDTO.LadderPriceDTO> toLadderPriceDTOList(List<ProductLadderPrice> ladderPriceList) {
        if (CollectionUtils.isEmpty(ladderPriceList)) {
            return Collections.emptyList();
        }
        List<ProductManageDetailDTO.LadderPriceDTO> result = new ArrayList<>();
        for (ProductLadderPrice ladderPrice : ladderPriceList) {
            ProductManageDetailDTO.LadderPriceDTO dto = new ProductManageDetailDTO.LadderPriceDTO();
            dto.setLadderPriceId(ladderPrice.getId());
            dto.setBizType(ladderPrice.getBizType());
            dto.setMinQuantity(ladderPrice.getMinQuantity());
            dto.setMaxQuantity(ladderPrice.getMaxQuantity());
            dto.setLadderPrice(ladderPrice.getLadderPrice());
            dto.setStatus(ladderPrice.getStatus());
            result.add(dto);
        }
        return result;
    }

    private List<ProductManageDetailDTO.WeightRuleDTO> toWeightRuleDTOList(List<ProductWeightRule> weightRuleList) {
        if (CollectionUtils.isEmpty(weightRuleList)) {
            return Collections.emptyList();
        }
        List<ProductManageDetailDTO.WeightRuleDTO> result = new ArrayList<>();
        for (ProductWeightRule weightRule : weightRuleList) {
            ProductManageDetailDTO.WeightRuleDTO dto = new ProductManageDetailDTO.WeightRuleDTO();
            dto.setWeightRuleId(weightRule.getId());
            dto.setBizType(weightRule.getBizType());
            dto.setPricingWeightType(weightRule.getPricingWeightType());
            dto.setWeightPrecision(weightRule.getWeightPrecision());
            dto.setMinWeight(weightRule.getMinWeight());
            dto.setMaxWeight(weightRule.getMaxWeight());
            dto.setStepWeight(weightRule.getStepWeight());
            dto.setRoundingMode(weightRule.getRoundingMode());
            dto.setExtConfigJson(weightRule.getExtConfigJson());
            dto.setStatus(weightRule.getStatus());
            result.add(dto);
        }
        return result;
    }

    private String buildSaleAttrKey(String attrName, String attrValue) {
        return trimToNull(attrName) + "::" + trimToNull(attrValue);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private <T> T defaultIfNull(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private static class StockState {
        private final int totalStock;
        private final int availableStock;
        private final int frozenStock;

        private StockState(int totalStock, int availableStock, int frozenStock) {
            this.totalStock = totalStock;
            this.availableStock = availableStock;
            this.frozenStock = frozenStock;
        }
    }

    private static class FinalSkuRef {
        private final ProductSaveReq.SkuReq skuReq;
        private final Long skuId;

        private FinalSkuRef(ProductSaveReq.SkuReq skuReq, Long skuId) {
            this.skuReq = skuReq;
            this.skuId = skuId;
        }
    }
}
