package com.aiolos.plaza.order.mq.consumer;

import com.aiolos.plaza.mq.message.OrderReserveMessage;
import com.aiolos.plaza.order.application.order.ReserveOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 监听普通单异步库存预占消息
 */
@Slf4j
@Component
public class OrderReserveConsumer {

    @Autowired
    private ReserveOrchestrator reserveOrchestrator;

    @Bean
    public Consumer<OrderReserveMessage> orderReserve() {
        return message -> {
            if (message == null || message.orderId() == null) {
                return;
            }
            log.info("收到普通单异步库存预占消息，orderId={}", message.orderId());
            reserveOrchestrator.handleAsyncReserve(message.orderId());
        };
    }
}
