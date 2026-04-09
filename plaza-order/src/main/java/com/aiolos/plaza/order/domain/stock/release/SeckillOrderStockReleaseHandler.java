package com.aiolos.plaza.order.domain.stock.release;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderType;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.order.application.stock.reservation.StockReservationService;
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
    private StockReservationService stockReservationService;

    @Override
    public Integer getOrderType() {
        return OrderType.SECKILL.getCode();
    }

    @Override
    public void release(OrderStockReleaseContext context) {
        Order order = context.getOrder();
        if (order.getReservationNo() != null) {
            stockReservationService.release(order.getReservationNo());
        }

        if (order.getActivityId() == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
        }
        if (order.getUserId() != null) {
            String boughtKey = RedisKeyEnum.SECKILL_BOUGHT_USERS.getKey(order.getActivityId());
            Long removed = shopRedisTemplate.opsForSet().remove(boughtKey, String.valueOf(order.getUserId()));
            if (removed != null && removed > 0) {
                context.setSeckillBoughtUserRemoved(true);
                int totalQuantity = 0;
                if (context.getOrderItems() != null) {
                    for (OrderItem item : context.getOrderItems()) {
                        if (item == null || item.getProductQuantity() == null || item.getProductQuantity() <= 0) {
                            continue;
                        }
                        totalQuantity += item.getProductQuantity();
                        context.getRedisIncrementedItems().add(item);
                    }
                }
                if (totalQuantity > 0) {
                    String stockKey = RedisKeyEnum.SECKILL_STOCK.getKey(order.getActivityId());
                    shopRedisTemplate.opsForValue().increment(stockKey, totalQuantity);
                }
            }
        }
    }

    @Override
    public void compensate(OrderStockReleaseContext context) {
        Order order = context.getOrder();
        if (order.getActivityId() != null && context.getRedisIncrementedItems() != null && !context.getRedisIncrementedItems().isEmpty()) {
            int totalQuantity = 0;
            for (OrderItem item : context.getRedisIncrementedItems()) {
                if (item == null || item.getProductQuantity() == null || item.getProductQuantity() <= 0) {
                    continue;
                }
                totalQuantity += item.getProductQuantity();
            }
            if (totalQuantity > 0) {
                String stockKey = RedisKeyEnum.SECKILL_STOCK.getKey(order.getActivityId());
                shopRedisTemplate.opsForValue().increment(stockKey, -totalQuantity);
            }
        }
        if (context.isSeckillBoughtUserRemoved() && order.getActivityId() != null && order.getUserId() != null) {
            shopRedisTemplate.opsForSet().add(RedisKeyEnum.SECKILL_BOUGHT_USERS.getKey(order.getActivityId()), String.valueOf(order.getUserId()));
        }
    }
}
