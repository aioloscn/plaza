package com.aiolos.plaza.product.model.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class ProductManageDetailDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long spuId;
    private Long shopId;
    private String spuName;
    private String spuCode;
    private Long categoryId;
    private Long brandId;
    private String mainImage;
    private String albumImages;
    private Integer productType;
    private Integer sourceType;
    private Integer status;
    private String description;
    private String extConfigJson;
    private List<MediaDTO> spuMediaList;
    private List<SaleAttrOptionDTO> saleAttrOptionList;
    private LocalExtDTO localExt;
    private EcommerceExtDTO ecommerceExt;
    private List<SkuDTO> skuList;

    @Data
    public static class SkuDTO implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long skuId;
        private String skuCode;
        private String skuName;
        private String barCode;
        private BigDecimal marketPrice;
        private BigDecimal salePrice;
        private BigDecimal costPrice;
        private Integer totalStock;
        private Integer availableStock;
        private Integer frozenStock;
        private Integer status;
        private BigDecimal defaultWeight;
        private String weightUnit;
        private BigDecimal defaultVolume;
        private String volumeUnit;
        private String imageUrl;
        private String extConfigJson;
        private List<SkuSaleAttrDTO> saleAttrList;
        private List<PublishDTO> publishList;
        private List<MediaDTO> mediaList;
        private List<LadderPriceDTO> ladderPriceList;
        private List<WeightRuleDTO> weightRuleList;
    }

    @Data
    public static class SaleAttrOptionDTO implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long saleAttrId;
        private String attrName;
        private String attrValue;
        private Integer sortNo;
        private Integer status;
    }

    @Data
    public static class SkuSaleAttrDTO implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long saleAttrId;
        private String attrName;
        private String attrValue;
    }

    @Data
    public static class PublishDTO implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long publishId;
        private Integer bizType;
        private BigDecimal channelSalePrice;
        private Integer saleStatus;
        private Integer visibleStatus;
        private Integer sortNo;
    }

    @Data
    public static class LadderPriceDTO implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long ladderPriceId;
        private Integer bizType;
        private Integer minQuantity;
        private Integer maxQuantity;
        private BigDecimal ladderPrice;
        private Integer status;
    }

    @Data
    public static class WeightRuleDTO implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long weightRuleId;
        private Integer bizType;
        private Integer pricingWeightType;
        private Integer weightPrecision;
        private BigDecimal minWeight;
        private BigDecimal maxWeight;
        private BigDecimal stepWeight;
        private Integer roundingMode;
        private String extConfigJson;
        private Integer status;
    }

    @Data
    public static class MediaDTO implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long mediaId;
        private Integer mediaType;
        private String mediaUrl;
        private Integer sortNo;
        private Integer status;
    }

    @Data
    public static class LocalExtDTO implements Serializable {
        private static final long serialVersionUID = 1L;

        private BigDecimal packingFee;
        private String unitName;
        private Integer minPurchaseQty;
        private Integer maxPurchaseQty;
        private Boolean supportTakeaway;
        private Boolean supportSelfPickup;
        private String saleTimeJson;
        private String tagJson;
        private String extConfigJson;
    }

    @Data
    public static class EcommerceExtDTO implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long logisticsTemplateId;
        private String deliveryOriginProvince;
        private String deliveryOriginCity;
        private String deliveryOriginRegion;
        private String deliveryOriginDetail;
        private String afterSalePolicy;
        private String deliveryChannelJson;
        private String extConfigJson;
    }
}
