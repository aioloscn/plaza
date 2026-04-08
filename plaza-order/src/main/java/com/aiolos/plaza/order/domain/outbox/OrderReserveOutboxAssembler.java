package com.aiolos.plaza.order.domain.outbox;

import com.aiolos.plaza.enums.MqLocalMessageType;
import com.aiolos.plaza.model.po.MqLocalMessage;
import com.aiolos.plaza.mq.constant.OrderMqConstants;
import com.aiolos.plaza.mq.message.OrderReserveMessage;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 库存预占本地消息组装器
 */
@Component
public class OrderReserveOutboxAssembler {

    private final ObjectMapper objectMapper;
    private final MqLocalMessageFactory mqLocalMessageFactory;

    public OrderReserveOutboxAssembler(ObjectMapper objectMapper,
                                       MqLocalMessageFactory mqLocalMessageFactory) {
        this.objectMapper = objectMapper;
        this.mqLocalMessageFactory = mqLocalMessageFactory;
    }

    public void assemble(OrderCreateContext context) {
        for (Long orderId : context.getOrderIds()) {
            OrderReserveMessage reserveMessage = new OrderReserveMessage(orderId);
            MqLocalMessage localMessage = mqLocalMessageFactory.build(
                    OrderMqConstants.BINDING_ORDER_RESERVE_OUT,
                    MqLocalMessageType.ORDER_RESERVE,
                    "order-reserve:" + orderId,
                    toJson(reserveMessage)
            );
            context.getLocalMessages().add(localMessage);
        }
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化库存预占本地消息失败", e);
        }
    }
}
