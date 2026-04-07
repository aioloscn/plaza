package com.aiolos.plaza.order.application.order;

import com.aiolos.plaza.order.application.confirm.ConfirmService;
import com.aiolos.plaza.order.chain.ChainExecutor;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.order.chain.handler.order.AddressCheckHandler;
import com.aiolos.plaza.order.chain.handler.order.CartFetchHandler;
import com.aiolos.plaza.order.chain.handler.order.DelayMessageSendHandler;
import com.aiolos.plaza.order.chain.handler.order.LocalMessageSaveHandler;
import com.aiolos.plaza.order.chain.handler.order.OrderBuildHandler;
import com.aiolos.plaza.order.chain.handler.order.OrderReserveMessagePrepareHandler;
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
public class SubmitAppService {

    @Autowired
    private ConfirmService confirmService;

    @Autowired
    private ChainExecutor chainExecutor;

    @Autowired
    private AddressCheckHandler addressCheckHandler;

    @Autowired
    private CartFetchHandler cartFetchHandler;

    @Autowired
    private OrderBuildHandler orderBuildHandler;

    @Autowired
    private LocalMessageSaveHandler localMessageSaveHandler;

    @Autowired
    private OrderReserveMessagePrepareHandler orderReserveMessagePrepareHandler;

    @Autowired
    private DelayMessageSendHandler delayMessageSendHandler;

    public String submit(Long userId, OrderSubmitReq req) {
        // 下单前先校验确认令牌，确保确认阶段与提交阶段的商品快照一致
        confirmService.validateConfirmToken(userId, req);

        OrderCreateContext context = new OrderCreateContext();
        context.setUserId(userId);
        context.setReq(req);

        List<ChainHandler<OrderCreateContext>> handlers = Arrays.asList(
                addressCheckHandler,
                cartFetchHandler,
                orderBuildHandler,
                orderReserveMessagePrepareHandler,
                delayMessageSendHandler,
                // 购物车删除改为依赖本地消息异步清理，避免主链路同步删购物车影响下单闭环
                localMessageSaveHandler
        );

        chainExecutor.execute(handlers, context);
        return context.getParentOrderSn();
    }
}
