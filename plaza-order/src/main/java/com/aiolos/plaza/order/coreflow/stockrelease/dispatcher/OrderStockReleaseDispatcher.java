package com.aiolos.plaza.order.coreflow.stockrelease.dispatcher;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderType;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.order.coreflow.stockrelease.handler.OrderStockReleaseHandler;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class OrderStockReleaseDispatcher {

    @Autowired
    private List<OrderStockReleaseHandler> handlers;

    private final Map<Integer, OrderStockReleaseHandler> handlerMap = new HashMap<>();

    @PostConstruct
    public void init() {
        for (OrderStockReleaseHandler handler : handlers) {
            handlerMap.put(handler.getOrderType(), handler);
        }
    }

    public OrderStockReleaseHandler getHandler(Integer orderType) {
        Integer actualOrderType = orderType == null ? OrderType.NORMAL.getCode() : orderType;
        OrderStockReleaseHandler handler = handlerMap.get(actualOrderType);
        if (handler == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
        }
        return handler;
    }
}
