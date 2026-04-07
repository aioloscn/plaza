package com.aiolos.plaza.order.application.order;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.order.config.OrderStateChangeInterceptor;
import com.aiolos.plaza.order.coreflow.inventory.service.OrderInventoryService;
import com.aiolos.plaza.order.domain.status.ParentStatusDomainService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 超时关闭应用服务
 * 负责批量超时关单、单笔取消与关闭确认推进
 */
@Service
public class TimeoutCloseService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderInventoryService orderInventoryService;

    @Autowired
    private StateMachineFactory<OrderState, OrderEvent> orderStateMachineFactory;

    @Autowired
    private OrderStateChangeInterceptor orderStateChangeInterceptor;

    @Autowired
    private ParentStatusDomainService parentStatusDomainService;

    @Transactional(rollbackFor = Exception.class)
    public void cancelTimeoutOrders() {
        LocalDateTime timeoutTime = LocalDateTime.now().minusMinutes(10);
        Set<String> affectedParentOrderSns = new HashSet<>();

        // 超时关闭分三段处理：锁库存超时直接取消；待支付超时先进入 CLOSING；
        // 已处于 CLOSING 且超过确认窗口的订单，再推进到真正 CLOSED，最后统一重算父单聚合状态
        handleReserveTimeout(timeoutTime, affectedParentOrderSns);
        handlePayTimeout(timeoutTime, affectedParentOrderSns);
        handleClosingConfirm(affectedParentOrderSns);

        affectedParentOrderSns.forEach(parentStatusDomainService::recomputeParentOrderStatus);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return;
        }
        if (isPaidOrAfter(order.getStatus()) || OrderState.CLOSED.getCode().equals(order.getStatus())) {
            return;
        }
        if (isRefundFlowStatus(order.getStatus())) {
            return;
        }
        if (OrderState.RESERVING.getCode().equals(order.getStatus())) {
            if (sendOrderEventWithDbState(order, OrderEvent.CANCEL, null, null, OrderExceptionEnum.ORDER_STATUS_ERROR)) {
                parentStatusDomainService.recomputeParentOrderStatus(order.getParentOrderSn());
            }
            return;
        }
        if (!OrderState.CREATED.getCode().equals(order.getStatus()) && !OrderState.PAYING.getCode().equals(order.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        // 严格由状态机驱动 CREATED/PAYING -> CLOSING，禁止手工改状态字段
        boolean accepted = sendOrderEventWithDbState(order, OrderEvent.START_CLOSE, null, null, OrderExceptionEnum.ORDER_STATUS_ERROR);
        if (!accepted) {
            return;
        }
        if (StringUtils.hasText(order.getReservationNo())) {
            orderInventoryService.extendExpireTime(order.getReservationNo(), now.plusMinutes(2));
        }
        parentStatusDomainService.recomputeParentOrderStatus(order.getParentOrderSn());
    }

    private void handleReserveTimeout(LocalDateTime timeoutTime, Set<String> affectedParentOrderSns) {
        for (Order order : orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderState.RESERVING.getCode())
                .le(Order::getCreateTime, timeoutTime))) {
            boolean accepted = sendOrderEventWithDbState(order, OrderEvent.CANCEL, null, null, OrderExceptionEnum.ORDER_STATUS_ERROR);
            if (accepted && StringUtils.hasText(order.getParentOrderSn())) {
                affectedParentOrderSns.add(order.getParentOrderSn());
            }
        }
    }

    private void handlePayTimeout(LocalDateTime timeoutTime, Set<String> affectedParentOrderSns) {
        LocalDateTime now = LocalDateTime.now();
        for (Order order : orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .in(Order::getStatus, Arrays.asList(OrderState.CREATED.getCode(), OrderState.PAYING.getCode()))
                .le(Order::getCreateTime, timeoutTime))) {
            boolean accepted = sendOrderEventWithDbState(order, OrderEvent.START_CLOSE, null, null, OrderExceptionEnum.ORDER_STATUS_ERROR);
            if (!accepted) {
                continue;
            }
            if (StringUtils.hasText(order.getReservationNo())) {
                orderInventoryService.extendExpireTime(order.getReservationNo(), now.plusMinutes(2));
            }
            if (StringUtils.hasText(order.getParentOrderSn())) {
                affectedParentOrderSns.add(order.getParentOrderSn());
            }
        }
    }

    private void handleClosingConfirm(Set<String> affectedParentOrderSns) {
        for (Order order : orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderState.CLOSING.getCode())
                .le(Order::getUpdateTime, LocalDateTime.now().minusMinutes(2)))) {
            Order latest = orderMapper.selectById(order.getId());
            if (latest == null || !OrderState.CLOSING.getCode().equals(latest.getStatus())) {
                continue;
            }
            boolean accepted = sendOrderEventWithDbState(latest, OrderEvent.CANCEL, null, null, OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
            if (!accepted) {
                continue;
            }
            if (StringUtils.hasText(latest.getParentOrderSn())) {
                affectedParentOrderSns.add(latest.getParentOrderSn());
            }
        }
    }

    /**
     * 状态机统一发送入口：
     * 1) 先把状态机恢复到数据库当前状态
     * 2) 再发送业务事件，确保流转校验基于真实状态而非初始状态
     */
    private boolean sendOrderEventWithDbState(Order order,
                                              OrderEvent event,
                                              LocalDateTime paymentTime,
                                              String reservationNo,
                                              OrderExceptionEnum errorEnum) {
        // 始终以数据库最新状态作为状态机 source，避免调用方传入对象过期导致并发误判
        Order latest = orderMapper.selectById(order.getId());
        if (latest == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        StateMachine<OrderState, OrderEvent> stateMachine = orderStateMachineFactory.getStateMachine(latest.getId().toString());
        stateMachine.getStateMachineAccessor().doWithAllRegions(access -> access.addStateMachineInterceptor(orderStateChangeInterceptor));
        stateMachine.stop();
        stateMachine.getStateMachineAccessor().doWithAllRegions(access ->
                access.resetStateMachine(new DefaultStateMachineContext<>(toOrderState(latest.getStatus()), null, null, null)));
        stateMachine.start();

        MessageBuilder<OrderEvent> builder = MessageBuilder.withPayload(event).setHeader("orderId", latest.getId());
        if (paymentTime != null) {
            builder.setHeader("paymentTime", paymentTime);
        }
        if (StringUtils.hasText(reservationNo)) {
            builder.setHeader("reservationNo", reservationNo);
        }
        boolean accepted = stateMachine.sendEvent(builder.build());
        if (stateMachine.hasStateMachineError()) {
            ExceptionUtil.throwException(errorEnum);
        }
        return accepted;
    }

    private boolean isPaidOrAfter(Integer status) {
        return OrderState.PAID.getCode().equals(status)
                || OrderState.DELIVERED.getCode().equals(status)
                || OrderState.COMPLETED.getCode().equals(status)
                || OrderState.REFUNDED.getCode().equals(status);
    }

    private boolean isRefundFlowStatus(Integer status) {
        return OrderState.PAY_RECOVERING.getCode().equals(status)
                || OrderState.REFUNDING.getCode().equals(status)
                || OrderState.REFUNDED.getCode().equals(status)
                || OrderState.REFUND_FAILED.getCode().equals(status);
    }

    private OrderState toOrderState(Integer statusCode) {
        for (OrderState value : OrderState.values()) {
            if (value.getCode().equals(statusCode)) {
                return value;
            }
        }
        ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        return OrderState.INVALID;
    }
}
