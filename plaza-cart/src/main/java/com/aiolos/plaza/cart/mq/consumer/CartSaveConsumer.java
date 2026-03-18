package com.aiolos.plaza.cart.mq.consumer;

import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.mq.message.CartAsyncSaveMessage;
import com.aiolos.plaza.service.CartItemService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Slf4j
@Component
public class CartSaveConsumer {

    @Resource
    private CartItemService cartItemService;

    @Bean
    public Consumer<CartAsyncSaveMessage> cartSave() {
        return message -> {
            log.info("Received cart save message: {}", message);
            
            try {
                // 判断操作类型
                if (message.getOperateType() != null && message.getOperateType() == 2) {
                    // 物理删除购物车商品
                    cartItemService.lambdaUpdate()
                            .eq(CartItem::getUserId, message.getUserId())
                            .eq(CartItem::getProductId, message.getProductId())
                            .remove();
                    log.info("Deleted cart item from MySQL, userId:{}, productId:{}", message.getUserId(), message.getProductId());
                    return;
                }

                // 检查数据库中是否已存在该商品
                CartItem existingItem = cartItemService.lambdaQuery().eq(CartItem::getUserId, message.getUserId()).eq(CartItem::getProductId, message.getProductId()).one();
                
                if (existingItem != null) {
                    // 更新
                    CartItem updateItem = new CartItem();
                    updateItem.setId(existingItem.getId());
                    updateItem.setQuantity(message.getQuantity());
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
