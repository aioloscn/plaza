package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.order.domain.outbox.CartClearOutboxAssembler;
import com.aiolos.plaza.order.domain.outbox.OrderReserveOutboxAssembler;
import com.aiolos.plaza.order.domain.outbox.OrderTimeoutOutboxAssembler;
import org.springframework.stereotype.Component;

/**
 * 订单 outbox 组装链路节点
 * 负责组装库存预占、延迟关单和购物车清理消息
 */
@Component
public class OrderOutboxAssembleHandler implements ChainHandler<OrderCreateContext> {

    private final OrderReserveOutboxAssembler orderReserveOutboxAssembler;
    private final OrderTimeoutOutboxAssembler orderTimeoutOutboxAssembler;
    private final CartClearOutboxAssembler cartClearOutboxAssembler;

    public OrderOutboxAssembleHandler(OrderReserveOutboxAssembler orderReserveOutboxAssembler,
                                      OrderTimeoutOutboxAssembler orderTimeoutOutboxAssembler,
                                      CartClearOutboxAssembler cartClearOutboxAssembler) {
        this.orderReserveOutboxAssembler = orderReserveOutboxAssembler;
        this.orderTimeoutOutboxAssembler = orderTimeoutOutboxAssembler;
        this.cartClearOutboxAssembler = cartClearOutboxAssembler;
    }

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        orderReserveOutboxAssembler.assemble(context);
        assembleTimeoutMessages(context);
        cartClearOutboxAssembler.assemble(context);
        chain.proceed(context);
    }

    private void assembleTimeoutMessages(OrderCreateContext context) {
        // 延迟关单统一走本地消息，真实投递交给任务触发
        for (Long orderId : context.getOrderIds()) {
            context.getLocalMessages().add(orderTimeoutOutboxAssembler.build(orderId));
        }
    }
}
