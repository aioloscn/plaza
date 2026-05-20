package com.aiolos.plaza.order.domain.stock.snapshot;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class InventoryProductSnapshot {
    /**
     * 统一库存快照主键，普通单/电商场景表示真实 skuId
     * 本地零售过渡期内，该值可能等于旧 product.id
     */
    private Long skuId;

    private Integer bizType;

    private Long shopId;

    private String productName;

    private String productImage;

    private Integer status;

    private Integer stock;

    private BigDecimal price;
}
