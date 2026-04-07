package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.order.domain.outbox.OrderTimeoutLocalMessageSupport;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class DelayMessageSendHandler implements ChainHandler<OrderCreateContext> {

    @Resource
    private OrderTimeoutLocalMessageSupport orderTimeoutLocalMessageSupport;

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        // 延迟关单也统一走本地消息：主事务只落库，真实发送交给 XXL 的 `mqMessageJob` 按到期时间异步投递
        for (Long orderId : context.getOrderIds()) {
            context.getLocalMessages().add(orderTimeoutLocalMessageSupport.build(orderId));
        }

        chain.proceed(context);
    }
}