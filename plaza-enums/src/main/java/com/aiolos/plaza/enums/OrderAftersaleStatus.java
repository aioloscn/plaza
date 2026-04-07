package com.aiolos.plaza.enums;

import lombok.Getter;

/**
 * 订单售后维度状态
 */
@Getter
public enum OrderAftersaleStatus {
    NONE(0, "无售后"),
    REFUNDING(1, "退款中"),
    PARTIALLY_REFUNDED(2, "部分已退款"),
    REFUNDED(3, "已退款"),
    REFUND_FAILED(4, "退款失败");

    private final Integer code;
    private final String desc;

    OrderAftersaleStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
