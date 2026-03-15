package com.aiolos.plaza.shop.controller;

import com.aiolos.common.cloud.annotation.AnonymousAuth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/cart")
public class CartController {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String CART_REDIS_PREFIX = "cart:";

    /**
     * 同步购物车数据到 Redis
     */
    @PostMapping("/sync")
    @AnonymousAuth
    public Boolean syncCart(@RequestBody Map<String, Object> payload) {
        String userId = (String) payload.get("userId");
        String tempUserId = (String) payload.get("tempUserId");
        // 这里如果是前端传来的对象，可能已经被反序列化成了 List，为了存入 redis 我们需要转回 JSON 字符串
        // 但简单起见，我们可以在前端将 cartItems 序列化为字符串后再传过来
        String cartItemsJson = (String) payload.get("cartItems"); 
        
        String key;
        if (userId != null && !userId.isEmpty()) {
            key = CART_REDIS_PREFIX + "user:" + userId;
        } else if (tempUserId != null && !tempUserId.isEmpty()) {
            key = CART_REDIS_PREFIX + "temp:" + tempUserId;
        } else {
            return false;
        }

        // 保存到 Redis，设置 7 天过期时间（游客数据）
        stringRedisTemplate.opsForValue().set(key, cartItemsJson, 7, TimeUnit.DAYS);
        return true;
    }

    /**
     * 获取 Redis 中的购物车数据
     */
    @GetMapping("/get")
    @AnonymousAuth
    public String getCart(@RequestParam(required = false) String userId, 
                          @RequestParam(required = false) String tempUserId) {
        String key;
        if (userId != null && !userId.isEmpty()) {
            key = CART_REDIS_PREFIX + "user:" + userId;
        } else if (tempUserId != null && !tempUserId.isEmpty()) {
            key = CART_REDIS_PREFIX + "temp:" + tempUserId;
        } else {
            return "[]";
        }

        String data = stringRedisTemplate.opsForValue().get(key);
        return data != null ? data : "[]";
    }
}
