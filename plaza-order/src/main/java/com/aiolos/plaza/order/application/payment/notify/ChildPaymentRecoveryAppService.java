package com.aiolos.plaza.order.application.payment.notify;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.application.payment.notify.model.PaymentResultCommand;
import com.aiolos.plaza.order.domain.order.status.OrderStateJudge;
import com.aiolos.plaza.order.domain.order.status.OrderStatusMetadataResolver;
import com.aiolos.plaza.order.statemachine.config.OrderStateMachineService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 子单支付恢复服务
 * 负责把支付成功后的子单从待支付或关单中间态收敛到已支付或退款中
 */
@Component
public class ChildPaymentRecoveryAppService {

    private final OrderMapper orderMapper;
    private final OrderStateJudge orderStateJudge;
    private final OrderStatusMetadataResolver orderStatusMetadataResolver;
    private final OrderStateMachineService orderStateMachineService;
    private final PaymentRefundTransitionAppService paymentRefundTransitionAppService;

    public ChildPaymentRecoveryAppService(OrderMapper orderMapper,
                                       OrderStateJudge orderStateJudge,
                                       OrderStatusMetadataResolver orderStatusMetadataResolver,
                                       OrderStateMachineService orderStateMachineService,
                                       PaymentRefundTransitionAppService paymentRefundTransitionAppService) {
        this.orderMapper = orderMapper;
        this.orderStateJudge = orderStateJudge;
        this.orderStatusMetadataResolver = orderStatusMetadataResolver;
        this.orderStateMachineService = orderStateMachineService;
        this.paymentRefundTransitionAppService = paymentRefundTransitionAppService;
    }

    public boolean hasClosedChildOrder(String parentOrderSn) {
        // 预检查阶段只关心是否存在已关闭或无效子单
        return listChildOrders(parentOrderSn).stream().anyMatch(this::isClosedOrInvalid);
    }

    public int processAfterPay(PaymentResultCommand command, ParentOrder parentOrder) {
        int updatedChildren = 0;
        // 支付成功后逐个收敛子单状态，统计真正发生恢复的数量
        for (Order child : listChildOrders(command.outTradeNo())) {
            if (processSingleChildOrderAfterPay(child, command, parentOrder)) {
                updatedChildren++;
            }
        }
        return updatedChildren;
    }

    private boolean processSingleChildOrderAfterPay(Order child, PaymentResultCommand command, ParentOrder parentOrder) {
        // 已经进入已支付及之后状态时无需重复推进
        if (orderStateJudge.isPaidOrAfter(child)) {
            return false;
        }
        // 待创建或支付中子单走正常 PAY 事件推进
        if (isCreatedOrPaying(child.getStatus())) {
            sendRequiredOrderEvent(child, OrderEvent.PAY, parentOrder.getPaymentTime(),
                    OrderExceptionEnum.ORDER_STATUS_ERROR, "支付");
            return false;
        }
        // 关单中的子单说明支付与关单并发，优先恢复为已支付
        if (OrderState.CLOSING.getCode().equals(child.getStatus())) {
            boolean recoverAccepted = orderStateMachineService.sendOrderEventWithDbState(child, OrderEvent.RECOVER_SUCCESS,
                    parentOrder.getPaymentTime(), OrderExceptionEnum.ORDER_STATUS_ERROR);
            if (!recoverAccepted) {
                // 状态机事件未接收时直接按数据库状态兜底到已支付
                orderMapper.update(null, orderStatusMetadataResolver.applyToOrderUpdate(
                                new LambdaUpdateWrapper<Order>(),
                                OrderState.PAID.getCode()
                        )
                        .set(Order::getPaymentTime, parentOrder.getPaymentTime())
                        .set(Order::getUpdateTime, parentOrder.getPaymentTime())
                        .eq(Order::getId, child.getId()));
            }
            return true;
        }
        return false;
    }

    public void markClosedChildrenRefunding(String parentOrderSn) {
        // 子单已经关闭时统一转入退款链路，由补偿逻辑后续接管
        paymentRefundTransitionAppService.markChildrenRefunding(parentOrderSn, "支付成功但子单关闭");
    }

    private void sendRequiredOrderEvent(Order child,
                                        OrderEvent event,
                                        java.time.LocalDateTime paymentTime,
                                        OrderExceptionEnum errorEnum,
                                        String actionDesc) {
        // 这里要求事件必须成功接收，否则直接按状态错误处理
        boolean accepted = orderStateMachineService.sendOrderEventWithDbState(child, event, paymentTime, errorEnum);
        if (!accepted) {
            ExceptionUtil.throwException(errorEnum);
        }
    }

    private List<Order> listChildOrders(String parentOrderSn) {
        // 回调按父单号装载整组子单，保证同一批次统一处理
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, parentOrderSn));
    }

    private boolean isClosedOrInvalid(Order child) {
        return child != null && (OrderState.CLOSED.getCode().equals(child.getStatus())
                || OrderState.INVALID.getCode().equals(child.getStatus()));
    }

    private boolean isCreatedOrPaying(Integer status) {
        return OrderState.CREATED.getCode().equals(status)
                || OrderState.PAYING.getCode().equals(status);
    }
}
