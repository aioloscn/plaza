package com.aiolos.plaza.cart.model.bo;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import java.io.Serializable;

@Data
public class CartAddReq implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 商品标识，统一命名为 skuId
     */
    @JsonAlias("productId")
    private Long skuId;

    /**
     * 业务类型：1-外卖/即时零售，2-电商
     */
    private Integer bizType;
    
    /**
     * 店铺ID
     */
    private Long shopId;
    
    /**
     * 添加数量
     */
    private Integer count;
}
