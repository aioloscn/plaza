package com.aiolos.plaza.order.coreflow.stockrelease.handler.impl;

import com.aiolos.plaza.enums.OrderType;
import com.aiolos.plaza.order.coreflow.stockrelease.context.OrderStockReleaseContext;
import com.aiolos.plaza.order.coreflow.stockrelease.handler.OrderStockReleaseHandler;
import com.aiolos.plaza.order.coreflow.inventory.service.OrderInventoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class NormalOrderStockReleaseHandler implements OrderStockReleaseHandler {

    @Autowired
    private OrderInventoryService orderInventoryService;

    @Override
    public Integer getOrderType() {
        return OrderType.NORMAL.getCode();
    }

    @Override
    public void release(OrderStockReleaseContext context) {
        if (context.getOrder() == null || context.getOrder().getReservationNo() == null) {
            return;
        }
        orderInventoryService.release(context.getOrder().getReservationNo());
    }

    @Override
    public void compensate(OrderStockReleaseContext context) {
    }
}
