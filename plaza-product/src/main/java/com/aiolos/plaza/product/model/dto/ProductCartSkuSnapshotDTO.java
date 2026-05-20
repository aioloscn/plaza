package com.aiolos.plaza.product.model.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class ProductCartSkuSnapshotDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long spuId;
    private Long skuId;
    private Long shopId;
    private Integer bizType;
    private String spuName;
    private String skuName;
    private String imageUrl;
    private String saleAttrJson;
    private BigDecimal salePrice;
    private Integer availableStock;
    private Integer status;
    private ProductWeightMetaDTO weightMeta;
}
