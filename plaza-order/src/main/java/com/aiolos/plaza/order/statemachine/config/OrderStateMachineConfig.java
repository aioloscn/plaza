package com.aiolos.plaza.order.statemachine.config;

import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.order.statemachine.action.OrderStockConfirmAction;
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

    @Autowired
    private OrderStockConfirmAction orderStockConfirmAction;

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
                    .source(OrderState.RESERVING).target(OrderState.CREATED).event(OrderEvent.RESERVE_SUCCESS)
                .and()
                .withExternal()
                    .source(OrderState.CREATED).target(OrderState.PAID).event(OrderEvent.PAY).action(orderStockConfirmAction)
                .and()
                .withExternal()
                    .source(OrderState.PAYING).target(OrderState.PAID).event(OrderEvent.PAY).action(orderStockConfirmAction)
                .and()
                .withExternal()
                    .source(OrderState.CREATED).target(OrderState.CLOSING).event(OrderEvent.START_CLOSE)
                .and()
                .withExternal()
                    .source(OrderState.PAYING).target(OrderState.CLOSING).event(OrderEvent.START_CLOSE)
                .and()
                .withExternal()
                    .source(OrderState.CLOSING).target(OrderState.PAY_RECOVERING).event(OrderEvent.PAY_CALLBACK)
                .and()
                .withExternal()
                    .source(OrderState.PAY_RECOVERING).target(OrderState.PAID).event(OrderEvent.RECOVER_SUCCESS).action(orderStockConfirmAction)
                .and()
                .withExternal()
                    .source(OrderState.PAY_RECOVERING).target(OrderState.REFUNDING).event(OrderEvent.RECOVER_FAIL)
                .and()
                .withExternal()
                    .source(OrderState.PAID).target(OrderState.REFUNDING).event(OrderEvent.APPLY_REFUND)
                .and()
                .withExternal()
                    .source(OrderState.REFUNDING).target(OrderState.REFUNDED).event(OrderEvent.REFUND_SUCCESS)
                .and()
                .withExternal()
                    .source(OrderState.REFUNDING).target(OrderState.REFUND_FAILED).event(OrderEvent.REFUND_FAIL)
                .and()
                .withExternal()
                    .source(OrderState.PAID).target(OrderState.DELIVERED).event(OrderEvent.DELIVER)
                .and()
                .withExternal()
                    .source(OrderState.DELIVERED).target(OrderState.COMPLETED).event(OrderEvent.RECEIVE)
                .and()
                .withExternal()
                    .source(OrderState.RESERVING).target(OrderState.CLOSED).event(OrderEvent.CANCEL).action(orderStockReleaseAction)
                .and()
                .withExternal()
                    .source(OrderState.CREATED).target(OrderState.CLOSED).event(OrderEvent.CANCEL).action(orderStockReleaseAction)
                .and()
                .withExternal()
                    .source(OrderState.PAYING).target(OrderState.CLOSED).event(OrderEvent.CANCEL).action(orderStockReleaseAction)
                .and()
                .withExternal()
                    .source(OrderState.CLOSING).target(OrderState.CLOSED).event(OrderEvent.CANCEL).action(orderStockReleaseAction);
    }
}
