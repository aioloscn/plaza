package com.aiolos.plaza.cart.model.bo;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class CartCheckReq implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 要更新选中状态的商品ID列表，如果为空则更新所有
     */
    private List<Long> productIds;
    
    /**
     * 是否选中 1:是 0:否
     */
    private Integer checked;
}
