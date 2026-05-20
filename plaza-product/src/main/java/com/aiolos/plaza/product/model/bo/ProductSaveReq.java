package com.aiolos.plaza.product.model.bo;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class ProductSaveReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * SPU ID，更新时必填
     */
    private Long spuId;

    /**
     * 归属店铺ID
     */
    private Long shopId;

    /**
     * SPU名称
     */
    private String spuName;

    /**
     * SPU编码
     */
    private String spuCode;

    /**
     * 类目ID
     */
    private Long categoryId;

    /**
     * 品牌ID
     */
    private Long brandId;

    /**
     * 主图
     */
    private String mainImage;

    /**
     * 图集JSON
     */
    private String albumImages;

    /**
     * 商品类型：1-普通商品，2-计重商品，3-套餐商品
     */
    private Integer productType;

    /**
     * 来源类型：1-商家录入，2-平台导入，3-外部同步
     */
    private Integer sourceType;

    /**
     * 状态：0-禁用，1-启用
     */
    private Integer status;

    /**
     * 描述
     */
    private String description;

    /**
     * SPU扩展配置JSON
     */
    private String extConfigJson;

    /**
     * SPU级素材
     */
    private List<MediaReq> spuMediaList;

    /**
     * 外卖/即时零售扩展
     */
    private LocalExtReq localExt;

    /**
     * 电商扩展
     */
    private EcommerceExtReq ecommerceExt;

    /**
     * SKU列表
     */
    private List<SkuReq> skuList;

    @Data
    public static class SkuReq implements Serializable {
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
        private List<SkuSaleAttrReq> saleAttrList;
        private List<PublishReq> publishList;
        private List<MediaReq> mediaList;
        private List<LadderPriceReq> ladderPriceList;
        private List<WeightRuleReq> weightRuleList;
    }

    @Data
    public static class SkuSaleAttrReq implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long saleAttrId;
        private String attrName;
        private String attrValue;
    }

    @Data
    public static class PublishReq implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long publishId;
        private Integer bizType;
        private BigDecimal channelSalePrice;
        private Integer saleStatus;
        private Integer visibleStatus;
        private Integer sortNo;
    }

    @Data
    public static class LadderPriceReq implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long ladderPriceId;
        private Integer bizType;
        private Integer minQuantity;
        private Integer maxQuantity;
        private BigDecimal ladderPrice;
        private Integer status;
    }

    @Data
    public static class WeightRuleReq implements Serializable {
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
    public static class MediaReq implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long mediaId;
        private Integer mediaType;
        private String mediaUrl;
        private Integer sortNo;
        private Integer status;
    }

    @Data
    public static class LocalExtReq implements Serializable {
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
    public static class EcommerceExtReq implements Serializable {
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
