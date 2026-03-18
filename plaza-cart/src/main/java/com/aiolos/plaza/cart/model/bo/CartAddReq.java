package com.aiolos.plaza.cart.model.bo;

import lombok.Data;
import java.io.Serializable;

@Data
public class CartAddReq implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 商品ID
     */
    private Long productId;
    
    /**
     * 店铺ID
     */
    private Long shopId;
    
    /**
     * 添加数量
     */
    private Integer count;
}
