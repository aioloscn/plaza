package com.aiolos.plaza.cart.mq.consumer;

import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.mq.message.CartAsyncSaveMessage;
import com.aiolos.plaza.service.CartItemService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Slf4j
@Component
public class CartSaveConsumer {

    @Resource
    private CartItemService cartItemService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Bean
    public Consumer<CartAsyncSaveMessage> cartChange() {
        return message -> {
            log.info("Received cart update message: {}", message);
            
            try {
                // 判断操作类型
                if (message.operateType() != null && message.operateType() == 2) {
                    // 1. 物理删除购物车数据库记录
                    boolean remove = cartItemService.lambdaUpdate()
                            .eq(CartItem::getUserId, message.userId())
                            .eq(CartItem::getProductId, message.productId())
                            .remove();

                    // 2. 物理删除 Redis 缓存（防止并发回源导致的脏数据复活）
                    String cartKey = RedisKeyEnum.CART_USER.getKey(message.userId());
                    Boolean delete = stringRedisTemplate.delete(cartKey);

                    log.info("Deleted cart item from MySQL and Redis, userId:{}, productId:{}, MySQL result: {}, Redis result: {}", 
                            message.userId(), message.productId(), remove, delete);
                    return;
                }

                // 检查数据库中是否已存在该商品
                CartItem existingItem = cartItemService.lambdaQuery().eq(CartItem::getUserId, message.userId()).eq(CartItem::getProductId, message.productId()).one();
                
                if (existingItem != null) {
                    // 更新
                    CartItem updateItem = new CartItem();
                    updateItem.setId(existingItem.getId());
                    updateItem.setQuantity(message.quantity());
                    updateItem.setUpdateTime(java.time.LocalDateTime.now());
                    cartItemService.updateById(updateItem);
                } else {
                    // 新增
                    CartItem newItem = new CartItem();
                    BeanUtils.copyProperties(message, newItem);
                    newItem.setCreateTime(java.time.LocalDateTime.now());
                    newItem.setUpdateTime(java.time.LocalDateTime.now());
                    cartItemService.save(newItem);
                }
            } catch (Exception e) {
                log.error("Failed to save cart item to MySQL", e);
            }
        };
    }
}
