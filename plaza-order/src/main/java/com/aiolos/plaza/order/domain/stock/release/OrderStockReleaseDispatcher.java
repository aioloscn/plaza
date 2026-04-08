package com.aiolos.plaza.order.domain.stock.release;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderType;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存释放处理器分发器
 * 按订单类型选择对应的库存释放处理实现
 */
@Component
public class OrderStockReleaseDispatcher {

    @Autowired
    private List<OrderStockReleaseHandler> handlers;

    private final Map<Integer, OrderStockReleaseHandler> handlerMap = new HashMap<>();

    @PostConstruct
    public void init() {
        // 启动时把所有处理器按 orderType 建立索引，运行时 O(1) 路由
        for (OrderStockReleaseHandler handler : handlers) {
            handlerMap.put(handler.getOrderType(), handler);
        }
    }

    /**
     * 按订单类型获取库存释放处理器，未传类型时默认按普通单处理
     */
    public OrderStockReleaseHandler getHandler(Integer orderType) {
        Integer actualOrderType = orderType == null ? OrderType.NORMAL.getCode() : orderType;
        OrderStockReleaseHandler handler = handlerMap.get(actualOrderType);
        if (handler == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
        }
        return handler;
    }
}
