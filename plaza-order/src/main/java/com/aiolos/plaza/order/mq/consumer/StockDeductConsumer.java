package com.aiolos.plaza.order.mq.consumer;

import com.aiolos.plaza.mapper.ProductMapper;
import com.aiolos.plaza.mapper.ProductStockLogMapper;
import com.aiolos.plaza.model.po.ProductStockLog;
import com.aiolos.plaza.mq.message.StockDeductMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.function.Consumer;

/**
 * 监听扣减数据库库存的异步消息
 */
@Slf4j
@Component
public class StockDeductConsumer {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductStockLogMapper productStockLogMapper;

    @Bean
    @Transactional(rollbackFor = Exception.class)
    public Consumer<StockDeductMessage> stockDeduct() {
        return message -> {
            log.info("收到扣减数据库库存消息: {}", message);
            try {
                if (message != null && message.productId() != null && message.quantity() != null) {
                    // 使用数据库层的乐观扣减，完成最终安全扣减
                    int rows = productMapper.deductStock(message.productId(), message.quantity());
                    if (rows > 0) {
                        log.info("数据库库存扣减成功，productId: {}, quantity: {}", message.productId(), message.quantity());
                        
                        // 记录库存操作日志
                        ProductStockLog stockLog = new ProductStockLog();
                        // 旧扣库存消息仍传 productId，这里先按本地零售单规格 skuId 口径落日志
                        stockLog.setSkuId(message.productId());
                        stockLog.setOrderSn(message.orderSn());
                        stockLog.setAmount(-message.quantity()); // 负数表示扣减
                        stockLog.setType(1); // 1-下单扣减
                        stockLog.setCreateTime(LocalDateTime.now());
                        productStockLogMapper.insert(stockLog);
                    } else {
                        log.warn("数据库库存扣减失败，可能是重复投递或库存不足，message: {}", message);
                        // 根据实际业务，如果确认属于超卖或异常，可抛异常触发 RocketMQ 重试，或发告警人工介入
                    }
                }
            } catch (Exception e) {
                log.error("处理扣减数据库库存消息异常: {}", message, e);
                throw new RuntimeException("处理扣减数据库库存消息异常", e); // 抛出异常触发重试
            }
        };
    }
}
