package com.aiolos.plaza.order.mq.consumer;

import com.aiolos.plaza.order.application.order.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Slf4j
@Component
public class OrderTimeoutConsumer {

    @Autowired
    private OrderService orderService;

    @Bean
    public Consumer<Long> orderTimeout() {
        return orderId -> {
            log.info("收到延迟取消订单消息，订单ID: {}", orderId);
            try {
                orderService.cancelOrder(orderId);
            } catch (Exception e) {
                log.error("处理延迟取消订单消息异常，订单ID: {}", orderId, e);
            }
        };
    }
}
