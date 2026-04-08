package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.service.MqLocalMessageService;
import org.springframework.stereotype.Component;

/**
 * outbox 落库链路节点
 * 负责把已组装的本地消息统一批量落库
 */
@Component
public class OutboxPersistenceHandler implements ChainHandler<OrderCreateContext> {

    private final MqLocalMessageService mqLocalMessageService;

    public OutboxPersistenceHandler(MqLocalMessageService mqLocalMessageService) {
        this.mqLocalMessageService = mqLocalMessageService;
    }

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        if (!context.getLocalMessages().isEmpty()) {
            mqLocalMessageService.saveBatch(context.getLocalMessages());
        }
        chain.proceed(context);
    }
}
