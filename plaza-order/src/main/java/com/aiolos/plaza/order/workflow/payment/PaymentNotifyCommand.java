package com.aiolos.plaza.order.workflow.payment;

import java.math.BigDecimal;

public record PaymentNotifyCommand(String outTradeNo,
                                   String tradeStatus,
                                   BigDecimal totalAmount,
                                   String tradeNo,
                                   String buyerId) {

    public boolean isTradeSuccess() {
        return "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
    }
}
