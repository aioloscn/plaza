package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.plaza.enums.MqLocalMessageType;
import com.aiolos.plaza.model.po.MqLocalMessage;
import com.aiolos.plaza.mq.constant.OrderMqConstants;
import com.aiolos.plaza.mq.message.OrderReserveMessage;
import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.order.domain.outbox.MqLocalMessageFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 为普通单准备异步库存预占本地消息
 * 订单主事务只负责落库，真正的库存预占交给消息消费者异步完成
 */
@Component
public class OrderReserveMessagePrepareHandler implements ChainHandler<OrderCreateContext> {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MqLocalMessageFactory mqLocalMessageFactory;

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        try {
            for (Long orderId : context.getOrderIds()) {
                OrderReserveMessage reserveMessage = new OrderReserveMessage(orderId);
                MqLocalMessage localMessage = mqLocalMessageFactory.build(
                        OrderMqConstants.BINDING_ORDER_RESERVE_OUT,
                        MqLocalMessageType.ORDER_RESERVE,
                        "order-reserve:" + orderId,
                        objectMapper.writeValueAsString(reserveMessage)
                );
                context.getLocalMessages().add(localMessage);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化库存预占本地消息失败", e);
        }
        chain.proceed(context);
    }
}
