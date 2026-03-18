package com.aiolos.plaza.shop.mq.producer;

import com.aiolos.plaza.mq.constant.ProductMqConstants;
import com.aiolos.plaza.mq.message.ProductCacheDeleteMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Slf4j
@Component
public class ProductMessageProducer {

    @Resource
    private StreamBridge streamBridge;

    /**
     * 发送商品缓存双删延迟消息
     * @param productId 商品ID
     * @param delayLevel 延迟级别 (1=1s, 2=5s, 3=10s, 4=30s, 5=1m, 6=2m)
     */
    public void sendCacheDeleteDelayMessage(Long productId, int delayLevel) {
        ProductCacheDeleteMessage messagePayload = new ProductCacheDeleteMessage(productId);
        
        Message<ProductCacheDeleteMessage> message = MessageBuilder.withPayload(messagePayload)
                .setHeader("DELAY", delayLevel)
                .build();
                
        boolean result = streamBridge.send(ProductMqConstants.PRODUCT_CACHE_DELETE_OUTPUT, message);
        if (result) {
            log.info("发送商品缓存双删延迟消息成功，商品ID: {}, 延迟级别: {}", productId, delayLevel);
        } else {
            log.error("发送商品缓存双删延迟消息失败，商品ID: {}", productId);
        }
    }
}
