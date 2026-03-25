package com.aiolos.plaza.mq.message;

import lombok.Builder;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 秒杀订单异步下单消息
 *
 * @param activityId 活动ID
 * @param shopId 店铺ID
 * @param userId 用户ID
 * @param productId 商品ID
 * @param price 秒杀价格
 * @param count 购买数量（通常为1）
 * @param addressId 收货地址ID
 */
@Builder
public record SeckillOrderMessage(
        Long activityId,
        Long shopId,
        Long userId,
        Long productId,
        BigDecimal price,
        Integer count,
        Long addressId
) implements Serializable {
}
