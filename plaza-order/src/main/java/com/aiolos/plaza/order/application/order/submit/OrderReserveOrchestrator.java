package com.aiolos.plaza.order.application.order.submit;

import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.StockScope;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.order.domain.stock.reservation.InventoryReserveItem;
import com.aiolos.plaza.order.application.order.status.ParentOrderRefreshAppService;
import com.aiolos.plaza.order.application.stock.reservation.StockReservationService;
import com.aiolos.plaza.order.domain.order.status.OrderStateJudge;
import com.aiolos.plaza.order.statemachine.config.OrderStateMachineService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 异步库存预占编排器
 * 负责普通单库存预占、失败收口以及状态机推进
 */
@Slf4j
@Component
public class OrderReserveOrchestrator {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private StockReservationService stockReservationService;

    @Autowired
    private ParentOrderRefreshAppService parentOrderRefreshAppService;

    @Autowired
    private OrderStateMachineService orderStateMachineService;

    @Autowired
    private OrderStateJudge orderStateJudge;

    /**
     * 普通单异步库存预占入口
     * 消费端只带 `orderId`，服务端按最新订单和订单项重建预占请求，避免消息体复制订单明细
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleAsyncReserve(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || !isReserving(order)) {
            return;
        }
        if (order.getCreateTime() != null && order.getCreateTime().plusMinutes(10).isBefore(LocalDateTime.now())) {
            if (orderStateMachineService.sendOrderEventWithDbState(order, OrderEvent.CANCEL, null, null, OrderExceptionEnum.ORDER_STATUS_ERROR)) {
                parentOrderRefreshAppService.refresh(order.getParentOrderSn());
            }
            return;
        }
        List<OrderItem> orderItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId));
        if (orderItems == null || orderItems.isEmpty()) {
            closeOrdersForReserveFailure(order, "订单明细不存在");
            return;
        }
        List<InventoryReserveItem> reserveItems = orderItems.stream()
                .collect(Collectors.groupingBy(OrderItem::getProductId, Collectors.summingInt(OrderItem::getProductQuantity)))
                .entrySet()
                .stream()
                .map(entry -> new InventoryReserveItem(entry.getKey(), null, entry.getValue()))
                .collect(Collectors.toList());
        try {
            LocalDateTime expireTime = order.getCreateTime() != null ? order.getCreateTime().plusMinutes(10) : LocalDateTime.now().plusMinutes(10);
            String reservationNo = stockReservationService.reserve(order.getOrderSn(), order.getUserId(), StockScope.NORMAL, null, reserveItems, expireTime);
            boolean accepted = orderStateMachineService.sendOrderEventWithDbState(order, OrderEvent.RESERVE_SUCCESS, null, reservationNo, OrderExceptionEnum.ORDER_STATUS_ERROR);
            if (accepted) {
                parentOrderRefreshAppService.refresh(order.getParentOrderSn());
            }
        } catch (Exception ex) {
            log.warn("异步库存预占失败，orderId={}", orderId, ex);
            closeOrdersForReserveFailure(order, ex.getMessage());
        }
    }

    /**
     * 任一子单异步预占失败时关闭同父订单下全部未支付子单，避免父单出现部分可支付的中间态
     */
    @Transactional(rollbackFor = Exception.class)
    public void closeOrdersForReserveFailure(Order failedOrder, String reason) {
        if (failedOrder == null || !StringUtils.hasText(failedOrder.getParentOrderSn())) {
            return;
        }
        List<Order> siblingOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, failedOrder.getParentOrderSn()));
        if (siblingOrders == null || siblingOrders.isEmpty()) {
            return;
        }
        for (Order sibling : siblingOrders) {
            Order latest = orderMapper.selectById(sibling.getId());
            if (latest == null || orderStateJudge.isPaidOrAfter(latest) || orderStateJudge.isClosed(latest)) {
                continue;
            }
            if (OrderState.RESERVING.getCode().equals(latest.getStatus())) {
                orderStateMachineService.sendOrderEventWithDbState(latest, OrderEvent.CANCEL, null, null, OrderExceptionEnum.ORDER_STATUS_ERROR);
                continue;
            }
            if (OrderState.CREATED.getCode().equals(latest.getStatus())
                    || OrderState.PAYING.getCode().equals(latest.getStatus())
                    || OrderState.CLOSING.getCode().equals(latest.getStatus())) {
                orderStateMachineService.sendOrderEventWithDbState(latest, OrderEvent.CANCEL, null, null, OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
            }
        }
        parentOrderRefreshAppService.refresh(failedOrder.getParentOrderSn());
        log.warn("父单异步预占失败已关闭未支付子单，parentOrderSn={}, reason={}", failedOrder.getParentOrderSn(), reason);
    }

    private boolean isReserving(Order order) {
        if (order == null) {
            return false;
        }
        return OrderState.RESERVING.getCode().equals(order.getStatus());
    }
}
