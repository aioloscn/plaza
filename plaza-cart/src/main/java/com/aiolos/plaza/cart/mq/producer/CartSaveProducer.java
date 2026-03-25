package com.aiolos.plaza.cart.mq.producer;

import com.aiolos.plaza.mq.constant.CartMqConstants;
import com.aiolos.plaza.mq.message.CartAsyncSaveMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 购物车异步落库生产者
 */
@Slf4j
@Component
public class CartSaveProducer {

    @Resource
    private StreamBridge streamBridge;

    public void sendCartSaveMessage(CartAsyncSaveMessage message) {
        streamBridge.send(CartMqConstants.BINDING_CART_CHANGE_OUT, MessageBuilder.withPayload(message).build());
        log.info("Sent cart save message for user: {}, product: {}", message.userId(), message.productId());
    }
}
