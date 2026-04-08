package com.aiolos.plaza.order.statemachine.action;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.order.application.stock.reservation.StockReservationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderStockConfirmAction implements Action<OrderState, OrderEvent> {

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private StockReservationService stockReservationService;

    @Override
    public void execute(StateContext<OrderState, OrderEvent> context) {
        Long orderId = (Long) context.getMessageHeader("orderId");
        if (orderId == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        if (order.getReservationNo() == null) {
            return;
        }
        try {
            stockReservationService.confirm(order.getReservationNo());
        } catch (Exception ex) {
            log.error("确认库存预占失败，orderId: {}, reservationNo: {}", orderId, order.getReservationNo(), ex);
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }
    }
}
