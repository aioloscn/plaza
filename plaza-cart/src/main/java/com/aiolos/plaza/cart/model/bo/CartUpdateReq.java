package com.aiolos.plaza.cart.model.bo;

import lombok.Data;
import java.io.Serializable;

@Data
public class CartUpdateReq implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 购物车项ID
     */
    private Long cartItemId;
    
    /**
     * 更新后的数量
     */
    private Integer count;
}
