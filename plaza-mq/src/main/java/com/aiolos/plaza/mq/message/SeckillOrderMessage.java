package com.aiolos.plaza.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 秒杀订单异步下单消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 活动ID */
    private Long activityId;

    /** 店铺ID */
    private Long shopId;

    /** 用户ID */
    private Long userId;

    /** 商品ID */
    private Long productId;

    /** 秒杀价格 */
    private BigDecimal price;

    /** 购买数量（通常为1） */
    private Integer count;
}
