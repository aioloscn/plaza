package com.aiolos.plaza.order.application.payment.notify.model;

import com.aiolos.plaza.model.po.ParentOrder;

public record PaymentResultContext(ParentOrder parentOrder,
                                   PaymentResultCommand command,
                                   PaymentResultPrecheck precheck) {
}
