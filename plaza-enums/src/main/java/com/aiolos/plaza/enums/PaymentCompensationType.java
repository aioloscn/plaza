package com.aiolos.plaza.enums;

import lombok.Getter;

/**
 * 支付补偿任务类型
 */
@Getter
public enum PaymentCompensationType {
    PAYMENT_QUERY(1, "支付结果查询"),
    REFUND_EXECUTE(2, "退款执行"),
    REFUND_RECONCILE(3, "退款对账");

    private final Integer code;
    private final String desc;

    PaymentCompensationType(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
