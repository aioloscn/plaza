package com.aiolos.plaza.order.mq.producer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderTxContext implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long activityId;
    private Long shopId;
    private Long userId;
    private Long productId;
    private BigDecimal price;
    private Integer count;
    private String parentOrderSn;
    private String orderSn;
    private Long addressId;
}
