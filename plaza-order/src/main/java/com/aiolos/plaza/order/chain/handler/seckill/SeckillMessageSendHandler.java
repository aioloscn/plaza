package com.aiolos.plaza.order.chain.handler.seckill;

import com.aiolos.plaza.mq.message.SeckillOrderMessage;
import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.SeckillOrderContext;
import com.aiolos.plaza.order.mq.producer.OrderMessageProducer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SeckillMessageSendHandler implements ChainHandler<SeckillOrderContext> {

    @Autowired
    private OrderMessageProducer orderMessageProducer;

    @Override
    public void handle(SeckillOrderContext context, Chain<SeckillOrderContext> chain) {
        SeckillOrderMessage message = SeckillOrderMessage.builder()
                .activityId(context.getReq().getActivityId())
                .shopId(context.getReq().getShopId())
                .userId(context.getUserId())
                .productId(context.getReq().getProductId())
                .price(context.getSeckillPrice())
                .count(1)
                .addressId(context.getReq().getAddressId())
                .build();

        orderMessageProducer.sendSeckillOrderTransactionMessage(message);
        context.setSuccess(true);
        
        chain.proceed(context);
    }
}