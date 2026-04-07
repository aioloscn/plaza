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
public class CartChangeConsumer {

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
                    boolean remove = cartItemService.lambdaUpdate()
                            .eq(CartItem::getId, message.cartItemId())
                            .eq(CartItem::getUserId, message.userId())
                            .remove();

                    // 2. 物理删除 Redis 缓存（防止并发回源导致的脏数据复活）
                    String cartKey = RedisKeyEnum.CART_USER.getKey(message.userId());
                    Boolean delete = stringRedisTemplate.delete(cartKey);

                    log.info("Deleted cart item from MySQL and Redis, userId:{}, productId:{}, cartItemId:{}, orderSn:{}, MySQL result: {}, Redis result: {}",
                            message.userId(), message.productId(), message.cartItemId(), message.orderSn(), remove, delete);
                    return;
                }

                CartItem existingItem = cartItemService.lambdaQuery()
                        .eq(CartItem::getId, message.cartItemId())
                        .eq(CartItem::getUserId, message.userId())
                        .one();
                
                if (existingItem != null) {
                    // 更新
                    CartItem updateItem = new CartItem();
                    updateItem.setId(existingItem.getId());
                    updateItem.setShopId(message.shopId());
                    updateItem.setProductId(message.productId());
                    updateItem.setQuantity(message.quantity());
                    updateItem.setChecked(message.checked());
                    updateItem.setPriceSnapshot(message.priceSnapshot());
                    updateItem.setProductName(message.productName());
                    updateItem.setProductImage(message.productImage());
                    updateItem.setStatus(message.status());
                    updateItem.setUpdateTime(java.time.LocalDateTime.now());
                    cartItemService.updateById(updateItem);
                } else {
                    // 新增
                    CartItem newItem = new CartItem();
                    BeanUtils.copyProperties(message, newItem);
                    newItem.setId(message.cartItemId());
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
