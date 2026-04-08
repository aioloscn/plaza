package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.plaza.order.application.order.write.OrderWriteService;
import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import org.springframework.stereotype.Component;

/**
 * 订单持久化链路节点
 * 负责把订单聚合持久化语义接入下单责任链
 */
@Component
public class OrderPersistenceHandler implements ChainHandler<OrderCreateContext> {

    private final OrderWriteService orderWriteService;

    public OrderPersistenceHandler(OrderWriteService orderWriteService) {
        this.orderWriteService = orderWriteService;
    }

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        orderWriteService.persist(context);
        chain.proceed(context);
    }
}
