package com.aiolos.plaza.order.mq.producer;

import com.aiolos.plaza.mq.constant.CartMqConstants;
import com.aiolos.plaza.mq.constant.OrderMqConstants;
import com.aiolos.plaza.mq.message.CartAsyncSaveMessage;
import com.aiolos.plaza.mq.message.SeckillOrderMessage;
import com.aiolos.plaza.mq.message.StockDeductMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 订单相关消息生产者
 */
@Slf4j
@Component
public class OrderMessageProducer {

    @Resource
    private StreamBridge streamBridge;

    @Resource(name = "seckillTxRocketMQTemplate")
    private RocketMQTemplate seckillTxRocketMQTemplate;

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

    /**
     * 秒杀事务消息发送流程：
     * 1) 先发送半消息到 stock-deduct-topic
     * 2) RocketMQ 回调本地事务监听器 executeLocalTransaction 执行下单事务
     * 3) 监听器返回 COMMIT/ROLLBACK 后，Broker 决定半消息是否对消费者可见
     * 4) 若事务状态不明确，Broker 后续会回查 checkLocalTransaction
     */
    public void sendSeckillOrderTransactionMessage(SeckillOrderMessage message) {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        int random = (int) (Math.random() * 9000 + 1000);
        String parentOrderSn = "P" + dateStr + random;
        String orderSn = "D" + dateStr + random;
        StockDeductMessage stockDeductMessage = new StockDeductMessage(message.getProductId(), message.getCount(), orderSn);
        SeckillOrderTxContext txContext = SeckillOrderTxContext.builder()
                .activityId(message.getActivityId())
                .shopId(message.getShopId())
                .userId(message.getUserId())
                .productId(message.getProductId())
                .price(message.getPrice())
                .count(message.getCount())
                .parentOrderSn(parentOrderSn)
                .orderSn(orderSn)
                .build();
        // 回查补偿需要的上下文字段，放入消息头，避免仅靠 payload 无法恢复活动维度信息
        Message<StockDeductMessage> txMessage = MessageBuilder.withPayload(stockDeductMessage)
                .setHeader("activityId", message.getActivityId())
                .setHeader("userId", message.getUserId())
                .setHeader("count", message.getCount())
                .build();
        // sendMessageInTransaction 会先发半消息，再触发本地事务回调
        TransactionSendResult sendResult = seckillTxRocketMQTemplate.sendMessageInTransaction(
                "stock-deduct-topic",
                txMessage,
                txContext
        );
        log.info("发送秒杀事务消息完成: orderSn={}, txStatus={}", orderSn, sendResult.getLocalTransactionState());
    }
}
