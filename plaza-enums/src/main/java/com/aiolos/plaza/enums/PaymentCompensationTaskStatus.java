package com.aiolos.plaza.enums;

import lombok.Getter;

/**
 * 支付补偿任务状态
 */
@Getter
public enum PaymentCompensationTaskStatus {
    INIT(0, "待执行"),
    PROCESSING(1, "处理中"),
    SUCCESS(2, "已完成"),
    RETRY(3, "待重试"),
    MANUAL_PENDING(4, "待人工介入"),
    CLOSED(5, "已关闭");

    private final Integer code;
    private final String desc;

    PaymentCompensationTaskStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
