package com.aiolos.plaza.enums;

public enum OrderEvent {
    /**
     * 支付
     */
    PAY,
    /**
     * 发货
     */
    DELIVER,
    /**
     * 确认收货
     */
    RECEIVE,
    /**
     * 完成
     */
    FINISH,
    /**
     * 取消
     */
    CANCEL,
    /**
     * 进入关闭确认中（用于 CREATED/PAYING -> CLOSING）
     */
    START_CLOSE,
    /**
     * 支付回调到达（用于 CLOSING -> PAY_RECOVERING）
     */
    PAY_CALLBACK,
    /**
     * 支付补偿成功（用于 PAY_RECOVERING -> PAID）
     */
    RECOVER_SUCCESS,
    /**
     * 支付补偿失败（用于 PAY_RECOVERING -> REFUNDING）
     */
    RECOVER_FAIL,
    /**
     * 预占成功（用于 RESERVING -> CREATED）
     */
    RESERVE_SUCCESS,
    /**
     * 用户申请退款（用于 PAID -> REFUNDING）
     */
    APPLY_REFUND,
    /**
     * 退款成功（用于 REFUNDING -> REFUNDED）
     */
    REFUND_SUCCESS,
    /**
     * 退款失败（用于 REFUNDING -> REFUND_FAILED）
     */
    REFUND_FAIL;
}
