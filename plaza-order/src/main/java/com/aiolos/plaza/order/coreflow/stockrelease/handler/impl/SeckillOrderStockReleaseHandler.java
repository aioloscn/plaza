package com.aiolos.plaza.order.coreflow.stockrelease.handler.impl;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderType;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.order.coreflow.inventory.service.OrderInventoryService;
import com.aiolos.plaza.order.coreflow.stockrelease.context.OrderStockReleaseContext;
import com.aiolos.plaza.order.coreflow.stockrelease.handler.OrderStockReleaseHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SeckillOrderStockReleaseHandler implements OrderStockReleaseHandler {

    @Autowired
    @Qualifier("shopRedisTemplate")
    private StringRedisTemplate shopRedisTemplate;

    @Autowired
    private OrderInventoryService orderInventoryService;

    @Override
    public Integer getOrderType() {
        return OrderType.SECKILL.getCode();
    }

    @Override
    public void release(OrderStockReleaseContext context) {
        Order order = context.getOrder();
        if (order.getReservationNo() != null) {
            orderInventoryService.release(order.getReservationNo());
        }

        if (order.getActivityId() == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
        }
        if (order.getUserId() != null) {
            shopRedisTemplate.opsForSet().remove(RedisKeyEnum.SECKILL_BOUGHT_USERS.getKey(order.getActivityId()), String.valueOf(order.getUserId()));
            context.setSeckillBoughtUserRemoved(true);
        }
    }

    @Override
    public void compensate(OrderStockReleaseContext context) {
        Order order = context.getOrder();
        if (context.isSeckillBoughtUserRemoved() && order.getActivityId() != null && order.getUserId() != null) {
            shopRedisTemplate.opsForSet().add(RedisKeyEnum.SECKILL_BOUGHT_USERS.getKey(order.getActivityId()), String.valueOf(order.getUserId()));
        }
    }
}
