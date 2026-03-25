package com.aiolos.plaza.order.mq.consumer;

import com.aiolos.plaza.mq.message.SeckillStockDeductMessage;
import com.aiolos.plaza.order.service.SeckillStockDeductService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 监听秒杀扣减库存的异步消息
 */
@Slf4j
@Component
public class SeckillStockDeductConsumer {

    @Resource
    private SeckillStockDeductService seckillStockDeductService;

    @Bean
    public Consumer<SeckillStockDeductMessage> seckillStockDeduct() {
        return message -> {
            log.info("收到秒杀扣减数据库库存消息: {}", message);
            try {
                seckillStockDeductService.consume(message);
            } catch (Exception e) {
                log.error("处理秒杀扣减数据库库存消息异常: {}", message, e);
                throw new RuntimeException("处理秒杀扣减数据库库存消息异常", e); // 抛出异常触发重试
            }
        };
    }
}
