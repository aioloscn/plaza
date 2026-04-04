package com.aiolos.plaza.order.config;

import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.messaging.Message;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.state.State;
import org.springframework.statemachine.support.StateMachineInterceptorAdapter;
import org.springframework.statemachine.transition.Transition;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

@Component
public class OrderStateChangeInterceptor extends StateMachineInterceptorAdapter<OrderState, OrderEvent> {

    private final OrderMapper orderMapper;

    public OrderStateChangeInterceptor(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    public void preStateChange(State<OrderState, OrderEvent> state, Message<OrderEvent> message,
                               Transition<OrderState, OrderEvent> transition, StateMachine<OrderState, OrderEvent> stateMachine) {
        Optional.ofNullable(message).flatMap(msg -> Optional.ofNullable((Long) msg.getHeaders().getOrDefault("orderId", -1L)))
                .ifPresent(orderId -> {
                    if (orderId != -1L) {
                        Order order = orderMapper.selectById(orderId);
                        if (order != null) {
                            Integer targetStatus = state.getId().getCode();
                            Integer sourceStatus = transition.getSource().getId().getCode();
                            LambdaUpdateWrapper<Order> updateWrapper = new LambdaUpdateWrapper<Order>()
                                    .set(Order::getStatus, targetStatus)
                                    .set(Order::getUpdateTime, LocalDateTime.now())
                                    // CAS：只有数据库当前状态仍等于 source 才允许落库，避免并发覆盖。
                                    .eq(Order::getId, orderId)
                                    .eq(Order::getStatus, sourceStatus);

                            // 检查并设置支付时间（如果事件中传递了）
                            if (message.getHeaders().containsKey("paymentTime")) {
                                LocalDateTime paymentTime = (LocalDateTime) message.getHeaders().get("paymentTime");
                                updateWrapper.set(Order::getPaymentTime, paymentTime);
                            }

                            int updated = orderMapper.update(null, updateWrapper);
                            if (updated <= 0) {
                                throw new IllegalStateException("Order state changed concurrently, state transition aborted");
                            }
                        }
                    }
                });
    }
    
    
}
