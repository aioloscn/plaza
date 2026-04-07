package com.aiolos.plaza.order.mq.consumer;

import com.aiolos.plaza.mq.message.OrderRefundMessage;
import com.aiolos.plaza.order.facade.PaymentFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 监听支付补偿失败后的退款消息
 */
@Slf4j
@Component
public class OrderRefundConsumer {

    @Autowired
    private PaymentFacade paymentService;

    @Bean
    public Consumer<OrderRefundMessage> orderRefund() {
        return message -> {
            if (message == null || message.parentOrderSn() == null) {
                return;
            }
            log.info("收到退款消息，parentOrderSn={}", message.parentOrderSn());
            paymentService.handleRefund(message.parentOrderSn());
        };
    }
}
