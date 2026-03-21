package com.aiolos.plaza.mq.constant;

public interface SeckillMqConstants {

    /**
     * 秒杀下单消息的 StreamBridge Binding Name
     */
    String BINDING_SECKILL_ORDER_OUT = "seckillOrder-out-0";

    /**
     * 秒杀下单消息的消费者函数名称
     */
    String FUNCTION_SECKILL_ORDER = "seckillOrderConsumer";
}
