package com.aiolos.plaza.orderno.provider.api;

public interface OrderNoApi {

    /**
     * 生成父订单号（普通聚合支付单）
     */
    String nextParentOrderSn();

    /**
     * 生成子订单号（店铺维度订单）
     */
    String nextChildOrderSn();

    /**
     * 生成秒杀订单号（高并发场景）
     */
    String nextSeckillOrderSn();

    /**
     * 按自定义前缀生成订单号
     */
    String nextOrderSn(String prefix);
}
