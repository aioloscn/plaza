package com.aiolos.plaza.order.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class OrderSubmitReq implements Serializable {
    
    /**
     * 收货地址ID
     */
    private Long addressId;
    
    /**
     * 店铺ID
     */
    private Long shopId;
    
    /**
     * 备注
     */
    private String note;
    
    /**
     * 支付方式：1->支付宝；2->微信
     */
    private Integer payType;
}
