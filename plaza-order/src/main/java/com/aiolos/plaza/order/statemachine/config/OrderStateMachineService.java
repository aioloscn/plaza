package com.aiolos.plaza.order.statemachine.config;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.model.po.Order;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 订单状态机执行服务
 * 统一基于数据库最新状态驱动订单事件，避免各链路重复拼装状态机调用
 */
@Component
public class OrderStateMachineService {

    private final OrderMapper orderMapper;
    private final StateMachineFactory<OrderState, OrderEvent> orderStateMachineFactory;
    private final OrderStateChangeInterceptor orderStateChangeInterceptor;

    public OrderStateMachineService(OrderMapper orderMapper,
                                    StateMachineFactory<OrderState, OrderEvent> orderStateMachineFactory,
                                    OrderStateChangeInterceptor orderStateChangeInterceptor) {
        this.orderMapper = orderMapper;
        this.orderStateMachineFactory = orderStateMachineFactory;
        this.orderStateChangeInterceptor = orderStateChangeInterceptor;
    }

    public boolean sendOrderEventWithDbState(Order order,
                                             OrderEvent event,
                                             LocalDateTime paymentTime,
                                             OrderExceptionEnum errorEnum) {
        return sendOrderEventWithDbState(order, event, paymentTime, null, errorEnum);
    }

    public boolean sendOrderEventWithDbState(Order order,
                                             OrderEvent event,
                                             LocalDateTime paymentTime,
                                             String reservationNo,
                                             OrderExceptionEnum errorEnum) {
        // 每次都基于数据库最新状态驱动事件，避免用调用方手里的旧对象推进状态机
        Order latest = orderMapper.selectById(order.getId());
        if (latest == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        // 先把状态机重置到数据库当前态，再发送本次事件
        StateMachine<OrderState, OrderEvent> stateMachine = orderStateMachineFactory.getStateMachine(latest.getId().toString());
        stateMachine.getStateMachineAccessor().doWithAllRegions(access -> access.addStateMachineInterceptor(orderStateChangeInterceptor));
        stateMachine.stop();
        stateMachine.getStateMachineAccessor().doWithAllRegions(access ->
                access.resetStateMachine(new DefaultStateMachineContext<>(toOrderState(latest.getStatus()), null, null, null)));
        stateMachine.start();

        MessageBuilder<OrderEvent> builder = MessageBuilder.withPayload(event).setHeader("orderId", latest.getId());
        if (paymentTime != null) {
            // 支付时间通过 header 透传给状态迁移拦截器和动作侧
            builder.setHeader("paymentTime", paymentTime);
        }
        if (reservationNo != null && !reservationNo.isBlank()) {
            // 异步预占成功时把 reservationNo 一并透传给拦截器落库
            builder.setHeader("reservationNo", reservationNo);
        }
        boolean accepted = stateMachine.sendEvent(builder.build());
        // 状态机内部报错时按链路约定抛业务异常，不静默吞掉
        if (stateMachine.hasStateMachineError()) {
            ExceptionUtil.throwException(errorEnum);
        }
        return accepted;
    }

    private OrderState toOrderState(Integer statusCode) {
        // 数据库存的是编码，这里统一映射回状态机枚举
        for (OrderState value : OrderState.values()) {
            if (value.getCode().equals(statusCode)) {
                return value;
            }
        }
        ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        return OrderState.INVALID;
    }
}
