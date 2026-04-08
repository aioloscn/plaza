package com.aiolos.plaza.order.application.order.submit;

import com.aiolos.plaza.order.chain.ChainExecutor;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.order.chain.handler.order.*;
import com.aiolos.plaza.order.model.bo.OrderSubmitReq;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 下单提交应用服务
 * 负责确认令牌校验和下单主链路编排
 */
@Service
public class OrderSubmitAppService {

    @Autowired
    private OrderConfirmAppService orderConfirmAppService;

    @Autowired
    private ChainExecutor chainExecutor;

    @Autowired
    private AddressCheckHandler addressCheckHandler;

    @Autowired
    private CartFetchHandler cartFetchHandler;

    @Autowired
    private OrderAggregateBuildHandler orderAggregateBuildHandler;

    @Autowired
    private OutboxPersistenceHandler outboxPersistenceHandler;

    @Autowired
    private OrderOutboxAssembleHandler orderOutboxAssembleHandler;

    @Autowired
    private OrderSnapshotValidateHandler orderSnapshotValidateHandler;

    @Autowired
    private OrderPersistenceHandler orderPersistenceHandler;

    public String submit(Long userId, OrderSubmitReq req) {
        // 下单前先校验确认令牌，确保确认阶段与提交阶段的商品快照一致
        orderConfirmAppService.validateConfirmToken(userId, req);

        OrderCreateContext context = new OrderCreateContext();
        context.setUserId(userId);
        context.setReq(req);

        List<ChainHandler<OrderCreateContext>> handlers = Arrays.asList(
                addressCheckHandler,
                cartFetchHandler,
                orderSnapshotValidateHandler,
                orderAggregateBuildHandler,
                orderPersistenceHandler,
                // 统一组装本地消息，包含库存预占、延迟关单和购物车删除
                orderOutboxAssembleHandler,
                // 统一落库，保证订单与 outbox 原子提交
                outboxPersistenceHandler
        );

        chainExecutor.execute(handlers, context);
        return context.getParentOrderSn();
    }
}
