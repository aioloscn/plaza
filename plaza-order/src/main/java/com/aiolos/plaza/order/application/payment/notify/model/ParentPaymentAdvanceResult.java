package com.aiolos.plaza.order.application.payment.notify.model;

import com.aiolos.plaza.model.po.ParentOrder;

/**
 * 父单支付推进结果
 * 用于通知编排层判断是否继续处理子单恢复与后续消息
 */
public record ParentPaymentAdvanceResult(boolean proceed, String response, ParentOrder parentOrder) {

    /**
     * 父单已成功推进到下一状态，通知编排层继续后续流程
     */
    public static ParentPaymentAdvanceResult proceed(ParentOrder parentOrder) {
        return new ParentPaymentAdvanceResult(true, null, parentOrder);
    }

    /**
     * 当前链路应立即停止，并把指定响应直接返回给上游
     */
    public static ParentPaymentAdvanceResult stop(String response) {
        return new ParentPaymentAdvanceResult(false, response, null);
    }
}
