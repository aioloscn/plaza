package com.aiolos.plaza.enums;

import lombok.Getter;

/**
 * 订单履约维度状态
 */
@Getter
public enum OrderFulfillmentStatus {
    UNFULFILLED(0, "待履约"),
    RESERVING(1, "锁库存中"),
    TO_DELIVER(2, "待发货"),
    PARTIALLY_DELIVERED(3, "部分已发货"),
    DELIVERED(4, "已发货"),
    COMPLETED(5, "已完成"),
    CLOSED(6, "已关闭");

    private final Integer code;
    private final String desc;

    OrderFulfillmentStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
