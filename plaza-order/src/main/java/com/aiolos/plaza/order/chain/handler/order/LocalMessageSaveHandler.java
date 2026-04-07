package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.plaza.enums.MqLocalMessageType;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.model.po.MqLocalMessage;
import com.aiolos.plaza.mq.constant.CartMqConstants;
import com.aiolos.plaza.mq.message.CartAsyncSaveMessage;
import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.order.domain.outbox.MqLocalMessageFactory;
import com.aiolos.plaza.service.MqLocalMessageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LocalMessageSaveHandler implements ChainHandler<OrderCreateContext> {

    @Autowired
    private MqLocalMessageService mqLocalMessageService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MqLocalMessageFactory mqLocalMessageFactory;

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        Long userId = context.getUserId();
        
        try {
            if (!context.getAllCartIds().isEmpty()) {
                // 购物车删除放到事务提交后的异步消息里做，主链路只负责保证订单与本地消息原子落库
                for (CartItem item : context.getCartItems()) {
                    if (item.getId() == null) {
                        continue;
                    }
                    CartAsyncSaveMessage cartMsg = new CartAsyncSaveMessage(
                            userId,
                            item.getShopId(),
                            item.getProductId(),
                            item.getId(),
                            context.getParentOrderSn(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            2
                    );

                    MqLocalMessage localMsg = mqLocalMessageFactory.build(
                            CartMqConstants.BINDING_CART_CHANGE_OUT,
                            MqLocalMessageType.CART_DELETE,
                            "order-cart-delete:" + context.getParentOrderSn() + ":" + item.getId(),
                            objectMapper.writeValueAsString(cartMsg)
                    );
                    context.getLocalMessages().add(localMsg);
                }
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化 MQ 本地消息失败", e);
        }

        if (!context.getLocalMessages().isEmpty()) {
            mqLocalMessageService.saveBatch(context.getLocalMessages());
        }
        
        chain.proceed(context);
    }
}
