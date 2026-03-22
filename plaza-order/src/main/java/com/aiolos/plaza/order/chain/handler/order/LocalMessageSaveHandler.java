package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.model.po.MqLocalMessage;
import com.aiolos.plaza.mq.constant.CartMqConstants;
import com.aiolos.plaza.mq.constant.OrderMqConstants;
import com.aiolos.plaza.mq.message.CartAsyncSaveMessage;
import com.aiolos.plaza.mq.message.StockDeductMessage;
import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.service.MqLocalMessageService;
import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class LocalMessageSaveHandler implements ChainHandler<OrderCreateContext> {

    @Autowired
    private MqLocalMessageService mqLocalMessageService;

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        Long userId = context.getUserId();
        
        for (StockDeductMessage msg : context.getStockDeductMessages()) {
            MqLocalMessage localMsg = new MqLocalMessage();
            localMsg.setTopic(OrderMqConstants.BINDING_STOCK_DEDUCT_OUT);
            localMsg.setContent(JSON.toJSONString(msg));
            localMsg.setState(0);
            localMsg.setRetryCount(0);
            localMsg.setBusinessKey(msg.orderSn());
            localMsg.setCreateTime(LocalDateTime.now());
            localMsg.setUpdateTime(LocalDateTime.now());
            context.getLocalMessages().add(localMsg);
        }

        if (!context.getAllCartIds().isEmpty()) {
            for (CartItem item : context.getCartItems()) {
                CartAsyncSaveMessage cartMsg = new CartAsyncSaveMessage(
                        userId,
                        null,
                        item.getProductId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        2 // 2表示删除
                );

                MqLocalMessage localMsg = new MqLocalMessage();
                localMsg.setTopic(CartMqConstants.BINDING_CART_SAVE_OUT);
                localMsg.setContent(JSON.toJSONString(cartMsg));
                localMsg.setState(0);
                localMsg.setRetryCount(0);
                localMsg.setBusinessKey(String.valueOf(userId));
                localMsg.setCreateTime(LocalDateTime.now());
                localMsg.setUpdateTime(LocalDateTime.now());
                context.getLocalMessages().add(localMsg);
            }
        }

        if (!context.getLocalMessages().isEmpty()) {
            mqLocalMessageService.saveBatch(context.getLocalMessages());
        }
        
        chain.proceed(context);
    }
}