package com.aiolos.plaza.order.domain.stock.release;

import com.aiolos.plaza.enums.OrderType;
import com.aiolos.plaza.order.application.stock.reservation.StockReservationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class NormalOrderStockReleaseHandler implements OrderStockReleaseHandler {

    @Autowired
    private StockReservationService stockReservationService;

    @Override
    public Integer getOrderType() {
        return OrderType.NORMAL.getCode();
    }

    @Override
    public void release(OrderStockReleaseContext context) {
        if (context.getOrder() == null || context.getOrder().getReservationNo() == null) {
            return;
        }
        stockReservationService.release(context.getOrder().getReservationNo());
    }

    @Override
    public void compensate(OrderStockReleaseContext context) {
    }
}
