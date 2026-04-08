package com.aiolos.plaza.order.application.order.close;

import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.order.application.order.status.ParentOrderRefreshAppService;
import com.aiolos.plaza.order.application.stock.reservation.StockReservationService;
import com.aiolos.plaza.order.domain.order.status.OrderStateJudge;
import com.aiolos.plaza.order.statemachine.config.OrderStateMachineService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
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
public class OrderCloseAppService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StockReservationService stockReservationService;

    @Autowired
    private OrderStateMachineService orderStateMachineService;

    @Autowired
    private ParentOrderRefreshAppService parentOrderRefreshAppService;

    @Autowired
    private OrderStateJudge orderStateJudge;

    @Transactional(rollbackFor = Exception.class)
    public void cancelTimeoutOrders() {
        LocalDateTime timeoutTime = LocalDateTime.now().minusMinutes(10);
        Set<String> affectedParentOrderSns = new HashSet<>();

        // 超时关闭分三段处理：锁库存超时直接取消；待支付超时先进入 CLOSING；
        // 已处于 CLOSING 且超过确认窗口的订单，再推进到真正 CLOSED，最后统一重算父单聚合状态
        handleReserveTimeout(timeoutTime, affectedParentOrderSns);
        handlePayTimeout(timeoutTime, affectedParentOrderSns);
        handleClosingConfirm(affectedParentOrderSns);

        parentOrderRefreshAppService.refreshAll(affectedParentOrderSns);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return;
        }
        if (orderStateJudge.isPaidOrAfter(order) || orderStateJudge.isClosed(order)) {
            return;
        }
        if (orderStateJudge.isRefundFlow(order)) {
            return;
        }
        if (OrderState.RESERVING.getCode().equals(order.getStatus())) {
            if (orderStateMachineService.sendOrderEventWithDbState(order, OrderEvent.CANCEL, null, null, OrderExceptionEnum.ORDER_STATUS_ERROR)) {
                parentOrderRefreshAppService.refresh(order.getParentOrderSn());
            }
            return;
        }
        if (!OrderState.CREATED.getCode().equals(order.getStatus()) && !OrderState.PAYING.getCode().equals(order.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        // 严格由状态机驱动 CREATED/PAYING -> CLOSING，禁止手工改状态字段
        boolean accepted = orderStateMachineService.sendOrderEventWithDbState(order, OrderEvent.START_CLOSE, null, null, OrderExceptionEnum.ORDER_STATUS_ERROR);
        if (!accepted) {
            return;
        }
        if (StringUtils.hasText(order.getReservationNo())) {
            stockReservationService.extendExpireTime(order.getReservationNo(), now.plusMinutes(2));
        }
        parentOrderRefreshAppService.refresh(order.getParentOrderSn());
    }

    private void handleReserveTimeout(LocalDateTime timeoutTime, Set<String> affectedParentOrderSns) {
        for (Order order : orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderState.RESERVING.getCode())
                .le(Order::getCreateTime, timeoutTime))) {
            boolean accepted = orderStateMachineService.sendOrderEventWithDbState(order, OrderEvent.CANCEL, null, null, OrderExceptionEnum.ORDER_STATUS_ERROR);
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
            boolean accepted = orderStateMachineService.sendOrderEventWithDbState(order, OrderEvent.START_CLOSE, null, null, OrderExceptionEnum.ORDER_STATUS_ERROR);
            if (!accepted) {
                continue;
            }
            if (StringUtils.hasText(order.getReservationNo())) {
                stockReservationService.extendExpireTime(order.getReservationNo(), now.plusMinutes(2));
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
            boolean accepted = orderStateMachineService.sendOrderEventWithDbState(latest, OrderEvent.CANCEL, null, null, OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
            if (!accepted) {
                continue;
            }
            if (StringUtils.hasText(latest.getParentOrderSn())) {
                affectedParentOrderSns.add(latest.getParentOrderSn());
            }
        }
    }
}
