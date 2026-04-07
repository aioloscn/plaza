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
    INVALID(5, "无效订单"),
    /**
     * 支付中
     */
    PAYING(6, "支付中"),
    /**
     * 关闭确认中（软关单）
     */
    CLOSING(7, "关闭确认中"),
    /**
     * 支付补偿中
     */
    PAY_RECOVERING(8, "支付补偿中"),
    /**
     * 退款处理中（中间态）
     */
    REFUNDING(9, "退款中"),
    /**
     * 已退款（终态）
     */
    REFUNDED(10, "已退款"),
    /**
     * 退款失败（终态）
     */
    REFUND_FAILED(11, "退款失败"),
    /**
     * 锁库存中
     */
    RESERVING(12, "锁库存中");

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
