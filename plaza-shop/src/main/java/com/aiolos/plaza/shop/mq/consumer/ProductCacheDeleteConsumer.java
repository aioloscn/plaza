package com.aiolos.plaza.shop.mq.consumer;

import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.mq.message.ProductCacheDeleteMessage;
import com.aiolos.plaza.shop.service.ShopProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.function.Consumer;

@Slf4j
@Configuration
public class ProductCacheDeleteConsumer {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    
    @Autowired
    private ShopProductService shopProductService;

    @Bean
    public Consumer<ProductCacheDeleteMessage> productCacheDelete() {
        return message -> {
            Long productId = message.productId();
            log.info("接收到商品缓存双删延迟消息，开始第二次删除缓存，商品ID: {}", productId);
            
            try {
                // 清理 Redis 缓存 L2
                stringRedisTemplate.delete(RedisKeyEnum.PRODUCT_INFO.getKey(productId));
                // 库存的缓存要不要删看业务需求，一般双删主要针对详情
                stringRedisTemplate.delete(RedisKeyEnum.PRODUCT_STOCK.getKey(productId));
                
                // 清理本地缓存 L1
                shopProductService.clearLocalCache(productId);
                
                log.info("商品缓存二次删除成功，商品ID: {}", productId);
            } catch (Exception e) {
                log.error("商品缓存二次删除失败，商品ID: {}", productId, e);
            }
        };
    }
}
