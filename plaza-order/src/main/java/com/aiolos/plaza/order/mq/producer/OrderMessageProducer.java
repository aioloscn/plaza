package com.aiolos.plaza.order.mq.producer;

import com.aiolos.plaza.mq.constant.OrderMqConstants;
import com.aiolos.plaza.mq.constant.CartMqConstants;
import com.aiolos.plaza.mq.message.CartAsyncSaveMessage;
import com.aiolos.plaza.mq.message.StockDeductMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 订单相关消息生产者
 */
@Slf4j
@Component
public class OrderMessageProducer {

    @Resource
    private StreamBridge streamBridge;

    /**
     * 发送库存扣减消息
     *
     * @param message 库存扣减消息体
     */
    public void sendStockDeductMessage(StockDeductMessage message) {
        try {
            streamBridge.send(OrderMqConstants.BINDING_STOCK_DEDUCT_OUT, MessageBuilder.withPayload(message).build());
            log.info("异步发送扣减库存消息成功: {}", message);
        } catch (Exception e) {
            log.error("异步发送扣减库存消息失败: {}", message, e);
        }
    }

    /**
     * 发送购物车保存/删除消息
     */
    public void sendCartSaveMessage(CartAsyncSaveMessage message) {
        try {
            streamBridge.send(CartMqConstants.BINDING_CART_SAVE_OUT, MessageBuilder.withPayload(message).build());
            log.info("异步发送购物车消息成功: {}", message);
        } catch (Exception e) {
            log.error("异步发送购物车消息失败: {}", message, e);
        }
    }

    /**
     * 发送订单超时取消延迟消息
     *
     * @param orderId 订单ID
     * @param delayLevel RocketMQ延迟级别
     */
    public void sendOrderTimeoutMessage(Long orderId, int delayLevel) {
        try {
            // 在 Spring Cloud Stream 中，通过 header "DELAY" 设置延迟级别
            Message<Long> timeoutMsg = MessageBuilder.withPayload(orderId)
                    .setHeader("DELAY", delayLevel)
                    .build();
            streamBridge.send(OrderMqConstants.BINDING_ORDER_TIMEOUT_OUT, timeoutMsg);
            log.info("发送订单超时取消延迟消息成功，订单ID: {}", orderId);
        } catch (Exception e) {
            log.error("发送订单超时取消延迟消息失败，订单ID: {}", orderId, e);
        }
    }
}