package com.aiolos.plaza.enums;

import lombok.Getter;

/**
 * 退款单状态
 */
@Getter
public enum RefundOrderStatus {
    INIT(0, "待退款"),
    PROCESSING(1, "退款处理中"),
    SUCCESS(2, "退款成功"),
    FAILED(3, "退款失败"),
    MANUAL_PENDING(4, "待人工介入"),
    CLOSED(5, "已关闭");

    private final Integer code;
    private final String desc;

    RefundOrderStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
