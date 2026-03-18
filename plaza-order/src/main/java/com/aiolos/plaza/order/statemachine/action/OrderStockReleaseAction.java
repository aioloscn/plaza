package com.aiolos.plaza.order.statemachine.action;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.ProductMapper;
import com.aiolos.plaza.model.po.OrderItem;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单取消时的库存归还动作
 */
@Slf4j
@Component
public class OrderStockReleaseAction implements Action<OrderState, OrderEvent> {

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    @Qualifier("shopRedisTemplate")
    private StringRedisTemplate shopRedisTemplate;

    private static final String PRODUCT_STOCK_PREFIX = "product:stock:";

    @Override
    public void execute(StateContext<OrderState, OrderEvent> context) {
        Long orderId = (Long) context.getMessageHeader("orderId");
        if (orderId == null) {
            String errorMsg = "归还库存失败：未找到订单ID";
            log.error(errorMsg);
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }

        try {
            // 归还库存 (数据库与Redis缓存)
            QueryWrapper<OrderItem> itemQuery = new QueryWrapper<>();
            itemQuery.eq("order_id", orderId);
            List<OrderItem> orderItems = orderItemMapper.selectList(itemQuery);
            
            for (OrderItem item : orderItems) {
                if (item.getProductId() != null && item.getProductQuantity() != null) {
                    productMapper.addStock(item.getProductId(), item.getProductQuantity());
                    // 恢复 Redis 缓存库存
                    shopRedisTemplate.opsForValue().increment(PRODUCT_STOCK_PREFIX + item.getProductId(), item.getProductQuantity());
                }
            }
            log.info("订单取消，库存归还成功，订单ID: {}", orderId);
        } catch (Exception e) {
            log.error("订单取消归还库存异常，订单ID: {}", orderId, e);
            // 必须抛出异常，以便外层事务回滚
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
        }
    }
}