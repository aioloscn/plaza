package com.aiolos.plaza.order.workflow.payment;

import com.aiolos.plaza.model.po.ParentOrder;

public record ParentPaymentAdvanceResult(boolean proceed, String response, ParentOrder parentOrder) {

    public static ParentPaymentAdvanceResult proceed(ParentOrder parentOrder) {
        return new ParentPaymentAdvanceResult(true, null, parentOrder);
    }

    public static ParentPaymentAdvanceResult stop(String response) {
        return new ParentPaymentAdvanceResult(false, response, null);
    }
}
