package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.order.domain.order.aggregate.OrderAggregateFactory;
import org.springframework.stereotype.Component;

/**
 * 订单聚合构建链路节点
 * 负责把订单聚合构建语义接入下单责任链
 */
@Component
public class OrderAggregateBuildHandler implements ChainHandler<OrderCreateContext> {

    private final OrderAggregateFactory orderAggregateFactory;

    public OrderAggregateBuildHandler(OrderAggregateFactory orderAggregateFactory) {
        this.orderAggregateFactory = orderAggregateFactory;
    }

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        orderAggregateFactory.build(context);
        chain.proceed(context);
    }
}
