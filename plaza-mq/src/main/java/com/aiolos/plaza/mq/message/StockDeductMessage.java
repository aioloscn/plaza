package com.aiolos.plaza.mq.message;

import java.io.Serializable;

/**
 * 扣减库存消息DTO
 * @param productId 商品ID
 * @param quantity  扣减数量
 * @param orderSn   关联的订单号 (可选，用于排查和幂等)
 */
public record StockDeductMessage(Long productId, Integer quantity, String orderSn) implements Serializable {}