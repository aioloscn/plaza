package com.aiolos.plaza.order.application.payment.notify.model;

/**
 * 支付回调预检查结果
 * hasClosedChild 表示是否已有子单进入关闭或关闭后态
 * hasClosingChild 表示是否仍有子单停留在关单中间态
 */
public record PaymentResultPrecheck(boolean hasClosedChild, boolean hasClosingChild) {
}
