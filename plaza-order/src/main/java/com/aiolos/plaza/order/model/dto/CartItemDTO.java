package com.aiolos.plaza.order.model.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class CartItemDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long id; // Cart item ID or Product ID
    private Long productId;
    private Long shopId;
    private String shopName;
    private String productName;
    private String productImage;
    private BigDecimal price;
    private Integer quantity;
    private Boolean checked;
    private Integer stock;
    private String status; // "VALID", "INVALID"
}
