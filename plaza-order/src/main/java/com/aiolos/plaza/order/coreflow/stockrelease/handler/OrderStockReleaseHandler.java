package com.aiolos.plaza.order.coreflow.stockrelease.handler;

import com.aiolos.plaza.order.coreflow.stockrelease.context.OrderStockReleaseContext;

public interface OrderStockReleaseHandler {

    Integer getOrderType();

    void release(OrderStockReleaseContext context);

    void compensate(OrderStockReleaseContext context);
}
