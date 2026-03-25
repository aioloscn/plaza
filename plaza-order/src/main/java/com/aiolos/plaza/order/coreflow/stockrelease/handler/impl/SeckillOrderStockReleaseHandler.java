package com.aiolos.plaza.order.coreflow.stockrelease.handler.impl;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderType;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.ProductStockLogMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ProductStockLog;
import com.aiolos.plaza.model.po.SeckillActivity;
import com.aiolos.plaza.order.coreflow.stockrelease.context.OrderStockReleaseContext;
import com.aiolos.plaza.order.coreflow.stockrelease.handler.OrderStockReleaseHandler;
import com.aiolos.plaza.service.SeckillActivityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class SeckillOrderStockReleaseHandler implements OrderStockReleaseHandler {

    @Autowired
    private SeckillActivityService seckillActivityService;

    @Autowired
    private ProductStockLogMapper productStockLogMapper;

    @Autowired
    @Qualifier("shopRedisTemplate")
    private StringRedisTemplate shopRedisTemplate;

    @Override
    public Integer getOrderType() {
        return OrderType.SECKILL.getCode();
    }

    @Override
    public void release(OrderStockReleaseContext context) {
        Order order = context.getOrder();
        Long activityId = order.getActivityId();
        if (activityId == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
        }

        for (OrderItem item : context.getOrderItems()) {
            if (item.getProductId() != null && item.getProductQuantity() != null) {
                seckillActivityService.lambdaUpdate()
                        .eq(SeckillActivity::getId, activityId)
                        .setSql("stock = stock + " + item.getProductQuantity())
                        .update();
                shopRedisTemplate.opsForValue().increment(RedisKeyEnum.SECKILL_STOCK.getKey(activityId), item.getProductQuantity());
                context.getRedisIncrementedItems().add(item);

                ProductStockLog stockLog = new ProductStockLog();
                stockLog.setProductId(item.getProductId());
                stockLog.setOrderSn(order.getOrderSn());
                stockLog.setAmount(item.getProductQuantity());
                stockLog.setType(2);
                stockLog.setCreateTime(LocalDateTime.now());
                productStockLogMapper.insert(stockLog);
            }
        }

        if (order.getUserId() != null) {
            shopRedisTemplate.opsForSet().remove(RedisKeyEnum.SECKILL_BOUGHT_USERS.getKey(activityId), String.valueOf(order.getUserId()));
            context.setSeckillBoughtUserRemoved(true);
        }
    }

    @Override
    public void compensate(OrderStockReleaseContext context) {
        Order order = context.getOrder();
        Long activityId = order.getActivityId();
        if (activityId != null) {
            for (OrderItem item : context.getRedisIncrementedItems()) {
                shopRedisTemplate.opsForValue().decrement(RedisKeyEnum.SECKILL_STOCK.getKey(activityId), item.getProductQuantity());
            }
        }

        if (context.isSeckillBoughtUserRemoved() && activityId != null && order.getUserId() != null) {
            shopRedisTemplate.opsForSet().add(RedisKeyEnum.SECKILL_BOUGHT_USERS.getKey(activityId), String.valueOf(order.getUserId()));
        }
    }
}
