package com.aiolos.plaza.order.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderListVO implements Serializable {
    private Long id;
    @Schema(description = "订单号")
    private String orderSn;
    @Schema(description = "父订单号")
    private String parentOrderSn;
    private Long shopId;
    private String shopName; // 需要关联店铺表
    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private Integer status;
    private String statusDesc;
    private LocalDateTime createTime;
    
    /**
     * 剩余支付时间（毫秒），前端可据此实现倒计时
     */
    private Long remainTime;
    
    private List<OrderItemVO> items;
}
