package com.aiolos.plaza.order.application.payment.notify.model;

import java.math.BigDecimal;

/**
 * 支付结果命令载体
 * 用于在支付回调链路中传递归一化后的关键字段
 */
public record PaymentResultCommand(String outTradeNo,
                                   String tradeStatus,
                                   BigDecimal totalAmount,
                                   String tradeNo,
                                   String buyerId) {

    /**
     * 统一判断第三方回调是否表示支付成功
     */
    public boolean isTradeSuccess() {
        return "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
    }
}
