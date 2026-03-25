package com.aiolos.plaza.mq.constant;

/**
 * 购物车相关 MQ 常量
 */
public class CartMqConstants {
    
    /**
     * 购物车异步落库 Binding Name (Output)
     * 对应 application.yml 中的 spring.cloud.stream.bindings.cartChange-out-0
     */
    public static final String BINDING_CART_CHANGE_OUT = "cartChange-out-0";
}
