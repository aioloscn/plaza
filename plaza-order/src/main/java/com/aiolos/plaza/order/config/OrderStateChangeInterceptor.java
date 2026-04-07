package com.aiolos.plaza.order.config;

import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.order.domain.status.OrderStatusMetadataResolver;
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
    private final OrderStatusMetadataResolver orderStatusMetadataResolver;

    public OrderStateChangeInterceptor(OrderMapper orderMapper,
                                       OrderStatusMetadataResolver orderStatusMetadataResolver) {
        this.orderMapper = orderMapper;
        this.orderStatusMetadataResolver = orderStatusMetadataResolver;
    }

    @Override
    /**
     * 在状态机切换前把目标状态通过 CAS 落到数据库，并同步写入事件携带的附加字段
     * 如果数据库当前状态已变化，则直接中断迁移，避免状态机内存状态和 DB 状态不一致
     * 注意：这里的数据库更新仍处于外层业务事务中，后续 action 若抛异常，当前这次状态落库也会一起回滚
     */
    public void preStateChange(State<OrderState, OrderEvent> state, Message<OrderEvent> message,
                               Transition<OrderState, OrderEvent> transition, StateMachine<OrderState, OrderEvent> stateMachine) {
        Optional.ofNullable(message).flatMap(msg -> Optional.ofNullable((Long) msg.getHeaders().getOrDefault("orderId", -1L)))
                .ifPresent(orderId -> {
                    if (orderId != -1L) {
                        Order order = orderMapper.selectById(orderId);
                        if (order != null) {
                            Integer targetStatus = state.getId().getCode();
                            Integer sourceStatus = transition.getSource().getId().getCode();
                            LambdaUpdateWrapper<Order> updateWrapper = orderStatusMetadataResolver.applyToOrderUpdate(
                                            new LambdaUpdateWrapper<Order>(),
                                            targetStatus
                                    )
                                    .set(Order::getUpdateTime, LocalDateTime.now())
                                    // CAS：只有数据库当前状态仍等于 source 才允许落库，避免并发覆盖
                                    .eq(Order::getId, orderId)
                                    .eq(Order::getStatus, sourceStatus);

                            // 检查并设置支付时间（如果事件中传递了）
                            if (message.getHeaders().containsKey("paymentTime")) {
                                LocalDateTime paymentTime = (LocalDateTime) message.getHeaders().get("paymentTime");
                                updateWrapper.set(Order::getPaymentTime, paymentTime);
                            }
                            // 异步预占成功时顺带写入 reservationNo，保证状态流转和预占号落库在同一次 CAS 更新里完成
                            if (message.getHeaders().containsKey("reservationNo")) {
                                String reservationNo = (String) message.getHeaders().get("reservationNo");
                                updateWrapper.set(Order::getReservationNo, reservationNo);
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
