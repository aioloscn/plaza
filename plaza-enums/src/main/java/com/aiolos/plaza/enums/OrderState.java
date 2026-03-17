package com.aiolos.plaza.enums;

public enum OrderState {
    /**
     * 待付款
     */
    CREATED(0, "待付款"),
    /**
     * 待发货
     */
    PAID(1, "待发货"),
    /**
     * 已发货
     */
    DELIVERED(2, "已发货"),
    /**
     * 已完成
     */
    COMPLETED(3, "已完成"),
    /**
     * 已关闭
     */
    CLOSED(4, "已关闭"),
    /**
     * 无效订单
     */
    INVALID(5, "无效订单");

    private final Integer code;
    private final String desc;

    OrderState(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
