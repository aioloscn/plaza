package com.aiolos.plaza.order.config;

import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.ParentOrder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.messaging.Message;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.state.State;
import org.springframework.statemachine.support.StateMachineInterceptorAdapter;
import org.springframework.statemachine.transition.Transition;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class OrderStateChangeInterceptor extends StateMachineInterceptorAdapter<OrderState, OrderEvent> {

    private final OrderMapper orderMapper;
    private final ParentOrderMapper parentOrderMapper;

    public OrderStateChangeInterceptor(OrderMapper orderMapper, ParentOrderMapper parentOrderMapper) {
        this.orderMapper = orderMapper;
        this.parentOrderMapper = parentOrderMapper;
    }

    @Override
    public void preStateChange(State<OrderState, OrderEvent> state, Message<OrderEvent> message,
                               Transition<OrderState, OrderEvent> transition, StateMachine<OrderState, OrderEvent> stateMachine) {
        Optional.ofNullable(message).flatMap(msg -> Optional.ofNullable((Long) msg.getHeaders().getOrDefault("orderId", -1L)))
                .ifPresent(orderId -> {
                    if (orderId != -1L) {
                        Order order = orderMapper.selectById(orderId);
                        if (order != null) {
                            order.setStatus(state.getId().getCode());
                            
                            // 检查并设置支付时间（如果事件中传递了）
                            if (message.getHeaders().containsKey("paymentTime")) {
                                java.time.LocalDateTime paymentTime = (java.time.LocalDateTime) message.getHeaders().get("paymentTime");
                                order.setPaymentTime(paymentTime);
                            }
                            
                            orderMapper.updateById(order);
                            
                            // 同步更新父订单状态
                            if (order.getParentOrderSn() != null) {
                                LambdaQueryWrapper<ParentOrder> query = new LambdaQueryWrapper<>();
                                query.eq(ParentOrder::getParentOrderSn, order.getParentOrderSn());
                                ParentOrder parentOrder = parentOrderMapper.selectOne(query);
                                if (parentOrder != null && !parentOrder.getStatus().equals(state.getId().getCode())) {
                                    parentOrder.setStatus(state.getId().getCode());
                                    parentOrderMapper.updateById(parentOrder);
                                }
                            }
                        }
                    }
                });
    }
}
