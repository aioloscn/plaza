package com.aiolos.plaza.order.domain.stock.release;

public interface OrderStockReleaseHandler {

    Integer getOrderType();

    void release(OrderStockReleaseContext context);

    void compensate(OrderStockReleaseContext context);
}
