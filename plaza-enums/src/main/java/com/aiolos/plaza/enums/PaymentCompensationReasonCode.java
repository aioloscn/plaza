package com.aiolos.plaza.enums;

import lombok.Getter;

/**
 * 支付补偿原因编码
 */
@Getter
public enum PaymentCompensationReasonCode {
    PAYMENT_CALLBACK_TIMEOUT("PAYMENT_CALLBACK_TIMEOUT", "支付回调缺失或超时"),
    PAYMENT_CALLBACK_PARENT_CLOSED("PAYMENT_CALLBACK_PARENT_CLOSED", "支付成功但父单已关闭"),
    PAYMENT_CALLBACK_CHILD_CLOSED("PAYMENT_CALLBACK_CHILD_CLOSED", "支付成功但存在已关闭子单"),
    PAYMENT_RECOVER_FAIL("PAYMENT_RECOVER_FAIL", "支付恢复失败"),
    PAYMENT_STATUS_CONFLICT("PAYMENT_STATUS_CONFLICT", "本地与三方支付状态冲突"),
    REFUND_REQUEST_CREATED("REFUND_REQUEST_CREATED", "已创建退款请求"),
    REFUND_EXECUTE_FAIL("REFUND_EXECUTE_FAIL", "退款执行失败"),
    REFUND_STATUS_UNKNOWN("REFUND_STATUS_UNKNOWN", "退款状态未知"),
    RECONCILE_DIFF("RECONCILE_DIFF", "支付或退款对账差异"),
    MANUAL_REQUIRED("MANUAL_REQUIRED", "需要人工介入");

    private final String code;
    private final String desc;

    PaymentCompensationReasonCode(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
