package com.aiolos.plaza.order.domain.outbox;

import com.aiolos.plaza.enums.MqLocalMessageType;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.model.po.MqLocalMessage;
import com.aiolos.plaza.mq.constant.CartMqConstants;
import com.aiolos.plaza.mq.message.CartAsyncSaveMessage;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 购物车清理本地消息组装器
 */
@Component
public class CartClearOutboxAssembler {

    private final ObjectMapper objectMapper;
    private final MqLocalMessageFactory mqLocalMessageFactory;

    public CartClearOutboxAssembler(ObjectMapper objectMapper,
                                    MqLocalMessageFactory mqLocalMessageFactory) {
        this.objectMapper = objectMapper;
        this.mqLocalMessageFactory = mqLocalMessageFactory;
    }

    public void assemble(OrderCreateContext context) {
        if (context.getAllCartIds().isEmpty()) {
            return;
        }
        Long userId = context.getUserId();
        for (CartItem item : context.getCartItems()) {
            if (item.getId() == null) {
                continue;
            }
            CartAsyncSaveMessage cartMessage = new CartAsyncSaveMessage(
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
            MqLocalMessage localMessage = mqLocalMessageFactory.build(
                    CartMqConstants.BINDING_CART_CHANGE_OUT,
                    MqLocalMessageType.CART_DELETE,
                    "order-cart-delete:" + context.getParentOrderSn() + ":" + item.getId(),
                    toJson(cartMessage)
            );
            context.getLocalMessages().add(localMessage);
        }
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化购物车清理本地消息失败", e);
        }
    }
}
