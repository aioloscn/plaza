package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.order.domain.order.snapshot.OrderSnapshotValidator;
import org.springframework.stereotype.Component;

/**
 * 商品快照校验链路节点
 * 负责把快照校验语义接入下单责任链
 */
@Component
public class OrderSnapshotValidateHandler implements ChainHandler<OrderCreateContext> {

    private final OrderSnapshotValidator orderSnapshotValidator;

    public OrderSnapshotValidateHandler(OrderSnapshotValidator orderSnapshotValidator) {
        this.orderSnapshotValidator = orderSnapshotValidator;
    }

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        orderSnapshotValidator.validateAndAttach(context);
        chain.proceed(context);
    }
}
