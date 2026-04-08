package com.aiolos.plaza.order.domain.outbox;

import com.aiolos.plaza.enums.MqLocalMessageType;
import com.aiolos.plaza.model.po.MqLocalMessage;
import com.aiolos.plaza.mq.constant.OrderMqConstants;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 订单超时关单本地消息构建器
 */
@Component
public class OrderTimeoutOutboxAssembler {

    private static final int ORDER_TIMEOUT_MINUTES = 10;

    @Resource
    private MqLocalMessageFactory mqLocalMessageFactory;

    public LocalDateTime calculateReadyTime(LocalDateTime createTime) {
        LocalDateTime baseTime = createTime == null ? LocalDateTime.now() : createTime;
        return baseTime.plusMinutes(ORDER_TIMEOUT_MINUTES);
    }

    public MqLocalMessage build(Long orderId) {
        return build(orderId, null);
    }

    public MqLocalMessage build(Long orderId, LocalDateTime createTime) {
        LocalDateTime readyTime = calculateReadyTime(createTime);
        // 显式使用 nextRetryTime 表达最早发送时间，避免继续挪用 tag
        return mqLocalMessageFactory.build(
                OrderMqConstants.BINDING_ORDER_TIMEOUT_OUT,
                MqLocalMessageType.ORDER_TIMEOUT,
                "order-timeout:" + orderId,
                String.valueOf(orderId),
                readyTime
        );
    }
}
