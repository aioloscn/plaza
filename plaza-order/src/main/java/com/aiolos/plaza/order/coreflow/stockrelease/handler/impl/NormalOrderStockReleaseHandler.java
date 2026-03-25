package com.aiolos.plaza.order.coreflow.stockrelease.handler.impl;

import com.aiolos.plaza.enums.OrderType;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.mapper.ProductMapper;
import com.aiolos.plaza.mapper.ProductStockLogMapper;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ProductStockLog;
import com.aiolos.plaza.order.coreflow.stockrelease.context.OrderStockReleaseContext;
import com.aiolos.plaza.order.coreflow.stockrelease.handler.OrderStockReleaseHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class NormalOrderStockReleaseHandler implements OrderStockReleaseHandler {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductStockLogMapper productStockLogMapper;

    @Autowired
    @Qualifier("shopRedisTemplate")
    private StringRedisTemplate shopRedisTemplate;

    @Override
    public Integer getOrderType() {
        return OrderType.NORMAL.getCode();
    }

    @Override
    public void release(OrderStockReleaseContext context) {
        for (OrderItem item : context.getOrderItems()) {
            if (item.getProductId() != null && item.getProductQuantity() != null) {
                productMapper.addStock(item.getProductId(), item.getProductQuantity());
                shopRedisTemplate.opsForValue().increment(RedisKeyEnum.PRODUCT_STOCK.getKey(item.getProductId()), item.getProductQuantity());
                context.getRedisIncrementedItems().add(item);

                ProductStockLog stockLog = new ProductStockLog();
                stockLog.setProductId(item.getProductId());
                stockLog.setOrderSn(context.getOrder().getOrderSn());
                stockLog.setAmount(item.getProductQuantity());
                stockLog.setType(2);
                stockLog.setCreateTime(LocalDateTime.now());
                productStockLogMapper.insert(stockLog);
            }
        }
    }

    @Override
    public void compensate(OrderStockReleaseContext context) {
        for (OrderItem item : context.getRedisIncrementedItems()) {
            shopRedisTemplate.opsForValue().decrement(RedisKeyEnum.PRODUCT_STOCK.getKey(item.getProductId()), item.getProductQuantity());
        }
    }
}
