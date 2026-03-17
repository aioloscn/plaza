package com.aiolos.plaza.order.vo;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderListVO implements Serializable {
    private Long id;
    private String orderSn;
    private Long shopId;
    private String shopName; // 需要关联店铺表
    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private Integer status;
    private String statusDesc;
    private LocalDateTime createTime;
    private List<OrderItemVO> items;
}
