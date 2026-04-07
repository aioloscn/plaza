package com.aiolos.plaza.order.workflow.payment;

import com.aiolos.plaza.model.po.ParentOrder;

public record PaymentNotifyContext(ParentOrder parentOrder,
                                   PaymentNotifyCommand command,
                                   PaymentNotifyPrecheck precheck) {
}
