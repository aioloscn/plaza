package com.aiolos.plaza.order.workflow.payment;

public record PaymentNotifyPrecheck(boolean hasClosedChild, boolean hasClosingChild) {
}
