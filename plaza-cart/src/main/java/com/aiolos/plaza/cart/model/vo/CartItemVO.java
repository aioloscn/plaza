package com.aiolos.plaza.cart.model.vo;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class CartItemVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long id; // cartItemId
    @JsonAlias("productId")
    private Long skuId;
    private Integer bizType;
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
