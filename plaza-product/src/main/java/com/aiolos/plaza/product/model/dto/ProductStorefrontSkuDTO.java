package com.aiolos.plaza.product.model.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class ProductStorefrontSkuDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 前台商品主键，统一返回真实 skuId
     */
    private Long skuId;

    private Long spuId;
    private Long shopId;
    private Integer bizType;
    private String name;
    private String description;
    private String imageUrl;
    private BigDecimal price;
    private Integer stock;
    private Integer status;
}
