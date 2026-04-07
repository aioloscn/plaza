package com.aiolos.plaza.enums;

import lombok.Getter;

/**
 * 本地消息类型
 * 用于统一治理重试、清理与后续人工介入策略
 */
@Getter
public enum MqLocalMessageType {
    ORDER_TIMEOUT("order_timeout", "订单超时关单", 7),
    ORDER_RESERVE("order_reserve", "订单库存预占", 7),
    ORDER_PAID("order_paid", "订单支付成功", 15),
    ORDER_REFUND("order_refund", "订单退款", 30),
    CART_DELETE("cart_delete", "购物车删除", 3);

    private final String code;
    private final String desc;
    private final int successRetentionDays;

    MqLocalMessageType(String code, String desc, int successRetentionDays) {
        this.code = code;
        this.desc = desc;
        this.successRetentionDays = successRetentionDays;
    }
}
