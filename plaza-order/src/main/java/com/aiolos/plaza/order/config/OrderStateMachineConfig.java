package com.aiolos.plaza.order.config;

import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.order.statemachine.action.OrderStockReleaseAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

/**
 * 订单状态机配置
 */
@Configuration
@EnableStateMachineFactory(name = "orderStateMachineFactory")
public class OrderStateMachineConfig extends EnumStateMachineConfigurerAdapter<OrderState, OrderEvent> {

    @Autowired
    private OrderStockReleaseAction orderStockReleaseAction;

    @Override
    public void configure(StateMachineStateConfigurer<OrderState, OrderEvent> states) throws Exception {
        states.withStates()
                .initial(OrderState.CREATED)
                .states(EnumSet.allOf(OrderState.class));
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderState, OrderEvent> transitions) throws Exception {
        transitions
                .withExternal()
                    .source(OrderState.CREATED).target(OrderState.PAID).event(OrderEvent.PAY)
                .and()
                .withExternal()
                    .source(OrderState.PAID).target(OrderState.DELIVERED).event(OrderEvent.DELIVER)
                .and()
                .withExternal()
                    .source(OrderState.DELIVERED).target(OrderState.COMPLETED).event(OrderEvent.RECEIVE)
                .and()
                .withExternal()
                    .source(OrderState.CREATED).target(OrderState.CLOSED).event(OrderEvent.CANCEL).action(orderStockReleaseAction)
                .and()
                .withExternal()
                    .source(OrderState.PAID).target(OrderState.CLOSED).event(OrderEvent.CANCEL).action(orderStockReleaseAction);
    }
}
