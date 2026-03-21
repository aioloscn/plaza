package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.mapper.CartItemMapper;
import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class CartClearHandler implements ChainHandler<OrderCreateContext> {

    @Autowired
    private CartItemMapper cartItemMapper;

    @Autowired
    @Qualifier("cartRedisTemplate")
    private StringRedisTemplate cartRedisTemplate;

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        Long userId = context.getUserId();
        
        if (!context.getAllCartIds().isEmpty()) {
            cartItemMapper.deleteBatchIds(context.getAllCartIds());
        }

        try {
            String cartKey = RedisKeyEnum.CART_USER.getKey(userId);
            Object[] productIds = context.getCartItems().stream()
                    .map(item -> String.valueOf(item.getProductId()))
                    .toArray();
            cartRedisTemplate.boundHashOps(cartKey).delete(productIds);

            Long size = cartRedisTemplate.boundHashOps(cartKey).size();
            if (size == null || size == 0) {
                String emptyMarkKey = RedisKeyEnum.CART_EMPTY_MARK_USER.getKey(userId);
                cartRedisTemplate.opsForValue().set(emptyMarkKey, "1", 60, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.error("清除Redis购物车缓存失败: userId={}", userId, e);
        }
        
        chain.proceed(context);
    }
}