package com.aiolos.plaza.enums;

import lombok.Getter;

/**
 * 本地消息表状态
 */
@Getter
public enum MqLocalMessageState {
    NEW(0, "新建"),
    SUCCESS(1, "成功"),
    FAIL(2, "失败"),
    PROCESSING(3, "处理中"),
    DEAD(4, "死信");

    private final Integer code;
    private final String desc;

    MqLocalMessageState(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
