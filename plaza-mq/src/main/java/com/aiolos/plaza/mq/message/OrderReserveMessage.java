package com.aiolos.plaza.mq.message;

import java.io.Serializable;

/**
 * 普通单异步库存预占消息
 * 只携带 orderId，消费端再按订单明细组装预占项，避免消息体与订单模型双写
 */
public record OrderReserveMessage(Long orderId) implements Serializable {
}
