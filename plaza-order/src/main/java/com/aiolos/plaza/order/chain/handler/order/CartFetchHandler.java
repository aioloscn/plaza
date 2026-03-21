package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.CartItemMapper;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.order.model.dto.CartItemDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CartFetchHandler implements ChainHandler<OrderCreateContext> {

    @Autowired
    @Qualifier("cartRedisTemplate")
    private StringRedisTemplate cartRedisTemplate;

    @Autowired
    private CartItemMapper cartItemMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        Long userId = context.getUserId();
        var req = context.getReq();

        List<CartItem> cartItems = new ArrayList<>();
        String cartKey = RedisKeyEnum.CART_USER.getKey(userId);
        Map<Object, Object> redisCart = cartRedisTemplate.opsForHash().entries(cartKey);

        if (redisCart != null && !redisCart.isEmpty()) {
            for (Object json : redisCart.values()) {
                try {
                    CartItemDTO cartItemDto = objectMapper.readValue(json.toString(), CartItemDTO.class);
                    if (Boolean.TRUE.equals(cartItemDto.getChecked())) {
                        if (req.getShopId() != null && !req.getShopId().equals(cartItemDto.getShopId())) {
                            continue;
                        }

                        CartItem cartItem = new CartItem();
                        BeanUtils.copyProperties(cartItemDto, cartItem);
                        cartItem.setUserId(userId);
                        cartItem.setProductImage(cartItemDto.getProductImage());
                        cartItem.setId(cartItemDto.getId());

                        cartItems.add(cartItem);
                    }
                } catch (Exception e) {
                    log.error("解析购物车Redis数据失败", e);
                }
            }
        }

        if (cartItems.isEmpty() && (redisCart == null || redisCart.isEmpty())) {
            LambdaQueryWrapper<CartItem> cartQuery = new LambdaQueryWrapper<>();
            cartQuery.eq(CartItem::getUserId, userId);
            if (req.getShopId() != null) {
                cartQuery.eq(CartItem::getShopId, req.getShopId());
            }
            cartQuery.eq(CartItem::getChecked, 1);
            cartItems = cartItemMapper.selectList(cartQuery);
        }

        if (cartItems == null || cartItems.isEmpty()) {
            ExceptionUtil.throwException(OrderExceptionEnum.CART_EMPTY);
        }

        context.setCartItems(cartItems);
        context.setShopCartMap(Objects.requireNonNull(cartItems).stream().collect(Collectors.groupingBy(CartItem::getShopId)));
                
        // 成功获取购物车数据后，继续执行下一个节点
        chain.proceed(context);
    }
}