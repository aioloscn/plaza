package com.aiolos.plaza.order.statemachine.action;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.order.coreflow.stockrelease.context.OrderStockReleaseContext;
import com.aiolos.plaza.order.coreflow.stockrelease.dispatcher.OrderStockReleaseDispatcher;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
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
    private OrderMapper orderMapper;

    @Autowired
    private OrderStockReleaseDispatcher orderStockReleaseDispatcher;

    @Override
    public void execute(StateContext<OrderState, OrderEvent> context) {
        Long orderId = (Long) context.getMessageHeader("orderId");
        if (orderId == null) {
            String errorMsg = "归还库存失败：未找到订单ID";
            log.error(errorMsg);
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        QueryWrapper<OrderItem> itemQuery = new QueryWrapper<>();
        itemQuery.eq("order_id", orderId);
        List<OrderItem> orderItems = orderItemMapper.selectList(itemQuery);
        if (orderItems.isEmpty()) {
            log.error("归还库存失败：订单 {} 不存在商品项", orderId);
        }
        OrderStockReleaseContext releaseContext = new OrderStockReleaseContext();
        releaseContext.setOrder(order);
        releaseContext.setOrderItems(orderItems);
        var releaseHandler = orderStockReleaseDispatcher.getHandler(order.getOrderType());

        try {
            releaseHandler.release(releaseContext);
            log.info("订单取消，库存归还成功，订单ID: {}", orderId);
        } catch (Exception e) {
            log.error("订单取消归还库存异常，订单ID: {}", orderId, e);
            try {
                releaseHandler.compensate(releaseContext);
            } catch (Exception compensateException) {
                log.error("订单取消归还库存补偿异常，订单ID: {}", orderId, compensateException);
            }
            // 当前 action 与前面的 preStateChange 落库处于同一外层事务中；
            // 这里标记回滚后，interceptor 已执行的 CLOSED 状态更新也会一起回滚
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
        }
    }
}
