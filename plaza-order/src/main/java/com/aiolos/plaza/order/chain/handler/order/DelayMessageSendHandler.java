package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.order.mq.producer.OrderMessageProducer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DelayMessageSendHandler implements ChainHandler<OrderCreateContext> {

    @Autowired
    private OrderMessageProducer orderMessageProducer;

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        for (Long orderId : context.getOrderIds()) {
            orderMessageProducer.sendOrderTimeoutMessage(orderId, 14);
        }
        
        chain.proceed(context);
    }
}