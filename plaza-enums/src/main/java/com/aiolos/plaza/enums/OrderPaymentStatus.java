package com.aiolos.plaza.enums;

import lombok.Getter;

/**
 * 订单支付维度状态
 */
@Getter
public enum OrderPaymentStatus {
    UNPAID(0, "未支付"),
    PAYING(1, "支付中"),
    PARTIAL_PAID(2, "部分已支付"),
    PAID(3, "已支付"),
    COMPENSATING(4, "支付补偿中"),
    REFUNDING(5, "退款中"),
    REFUNDED(6, "已退款"),
    REFUND_FAILED(7, "退款失败");

    private final Integer code;
    private final String desc;

    OrderPaymentStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
