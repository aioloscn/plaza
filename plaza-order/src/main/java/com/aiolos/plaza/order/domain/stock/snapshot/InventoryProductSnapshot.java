package com.aiolos.plaza.order.domain.stock.snapshot;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class InventoryProductSnapshot {
    private Long productId;
    private Long shopId;
    private String productName;
    private String productImage;
    private Integer status;
    private Integer stock;
    private BigDecimal price;
}
