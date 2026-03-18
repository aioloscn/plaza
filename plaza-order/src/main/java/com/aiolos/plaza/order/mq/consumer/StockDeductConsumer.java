package com.aiolos.plaza.order.mq.consumer;

import com.aiolos.plaza.mapper.ProductMapper;
import com.aiolos.plaza.mq.message.StockDeductMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 监听扣减库存的异步消息
 */
@Slf4j
@Component
public class StockDeductConsumer {

    @Autowired
    private ProductMapper productMapper;

    @Bean
    public Consumer<StockDeductMessage> stockDeduct() {
        return message -> {
            log.info("收到扣减数据库库存消息: {}", message);
            try {
                if (message != null && message.getProductId() != null && message.getQuantity() != null) {
                    // 使用数据库的乐观锁进行最终的安全扣减
                    int rows = productMapper.deductStock(message.getProductId(), message.getQuantity());
                    if (rows > 0) {
                        log.info("数据库库存扣减成功, productId: {}, quantity: {}", message.getProductId(), message.getQuantity());
                    } else {
                        log.warn("数据库库存扣减失败(可能是由于多次投递或者库存不足), message: {}", message);
                        // 根据实际业务，如果确认为超卖或异常，可以抛出异常触发RocketMQ重试，或者发送告警人工干预。
                    }
                }
            } catch (Exception e) {
                log.error("处理扣减数据库库存消息异常: {}", message, e);
                throw new RuntimeException("处理扣减数据库库存消息异常", e); // 抛出异常触发重试
            }
        };
    }
}
