package com.aiolos.plaza.order.model.bo;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;

@Data
public class OrderSubmitReq implements Serializable {
    
    /**
     * 收货地址ID
     */
    private Long addressId;
    
    /**
     * 店铺ID (已废弃，使用 shopNotes 替代)
     */
    private Long shopId;
    
    /**
     * 店铺备注映射（shopId -> note）
     */
    private Map<Long, String> shopNotes;

    /**
     * 支付方式：1 -> 支付宝；2 -> 微信
     */
    private Integer payType;

    private String confirmToken;
}
