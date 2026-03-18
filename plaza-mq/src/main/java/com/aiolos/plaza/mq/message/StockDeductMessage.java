package com.aiolos.plaza.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 扣减库存消息DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockDeductMessage implements Serializable {
    
    private static final long serialVersionUID = 1L;

    /**
     * 商品ID
     */
    private Long productId;

    /**
     * 扣减数量
     */
    private Integer quantity;

    /**
     * 关联的订单号 (可选，用于排查和幂等)
     */
    private String orderSn;
}