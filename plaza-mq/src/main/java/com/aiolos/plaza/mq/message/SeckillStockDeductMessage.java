package com.aiolos.plaza.mq.message;

import java.io.Serializable;

/**
 * 秒杀扣减库存消息DTO
 * @param activityId 秒杀活动ID
 * @param productId  商品ID
 * @param quantity   扣减数量
 * @param orderSn    关联的订单号 (可选，用于排查和幂等)
 */
public record SeckillStockDeductMessage(Long activityId, Long productId, Integer quantity, String orderSn) implements Serializable {}
