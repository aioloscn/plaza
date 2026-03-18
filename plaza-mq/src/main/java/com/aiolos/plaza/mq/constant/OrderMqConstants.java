package com.aiolos.plaza.mq.constant;

/**
 * 订单相关 MQ 常量
 */
public class OrderMqConstants {
    
    /**
     * 扣减库存 Binding Name (Output)
     * 对应 application.yml 中的 spring.cloud.stream.bindings.stockDeduct-out-0
     */
    public static final String BINDING_STOCK_DEDUCT_OUT = "stockDeduct-out-0";

    /**
     * 订单超时取消 Binding Name (Output)
     * 对应 application.yml 中的 spring.cloud.stream.bindings.orderTimeout-out-0
     */
    public static final String BINDING_ORDER_TIMEOUT_OUT = "orderTimeout-out-0";
}