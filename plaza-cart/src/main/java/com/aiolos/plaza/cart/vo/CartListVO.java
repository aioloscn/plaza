package com.aiolos.plaza.cart.vo;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
public class CartListVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private List<CartItemVO> items;
    private BigDecimal totalPrice;
}
