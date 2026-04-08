package com.aiolos.plaza.order.application.payment.refund;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.PaymentCompensationReasonCode;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.application.order.status.ParentOrderRefreshAppService;
import com.aiolos.plaza.order.application.payment.compensation.PaymentCompensationTaskScheduler;
import com.aiolos.plaza.order.application.payment.compensation.RefundCompensationAppService;
import com.aiolos.plaza.order.domain.order.status.OrderStateJudge;
import com.aiolos.plaza.order.statemachine.config.OrderStateMachineService;
import com.aiolos.plaza.order.application.payment.notify.PaymentRefundTransitionAppService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 用户主动退款申请服务
 * 负责校验退款资格、推进父子单到退款中并触发退款补偿任务
 */
@Service
public class UserRefundAppService {

    private final ParentOrderMapper parentOrderMapper;
    private final OrderMapper orderMapper;
    private final OrderStateJudge orderStateJudge;
    private final OrderStateMachineService orderStateMachineService;
    private final PaymentRefundTransitionAppService paymentRefundTransitionAppService;
    private final ParentOrderRefreshAppService parentOrderRefreshAppService;
    private final RefundCompensationAppService refundCompensationAppService;
    private final PaymentCompensationTaskScheduler paymentCompensationTaskScheduler;

    public UserRefundAppService(ParentOrderMapper parentOrderMapper,
                             OrderMapper orderMapper,
                             OrderStateJudge orderStateJudge,
                             OrderStateMachineService orderStateMachineService,
                             PaymentRefundTransitionAppService paymentRefundTransitionAppService,
                             ParentOrderRefreshAppService parentOrderRefreshAppService,
                             RefundCompensationAppService refundCompensationAppService,
                             PaymentCompensationTaskScheduler paymentCompensationTaskScheduler) {
        this.parentOrderMapper = parentOrderMapper;
        this.orderMapper = orderMapper;
        this.orderStateJudge = orderStateJudge;
        this.orderStateMachineService = orderStateMachineService;
        this.paymentRefundTransitionAppService = paymentRefundTransitionAppService;
        this.parentOrderRefreshAppService = parentOrderRefreshAppService;
        this.refundCompensationAppService = refundCompensationAppService;
        this.paymentCompensationTaskScheduler = paymentCompensationTaskScheduler;
    }

    public String apply(Long userId, String parentOrderSn) {
        if (!StringUtils.hasText(parentOrderSn)) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        ParentOrder parentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, parentOrderSn)
                .eq(ParentOrder::getUserId, userId)
                .last("LIMIT 1"));
        if (parentOrder == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        if (!orderStateJudge.isPaidOrAfter(parentOrder)
                || orderStateJudge.isRefundFlow(parentOrder)
                || orderStateJudge.isClosed(parentOrder)) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }

        List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, parentOrderSn));
        if (childOrders == null || childOrders.isEmpty()) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        for (Order child : childOrders) {
            if (!orderStateJudge.isPaidOrAfter(child)
                    || orderStateJudge.isRefundFlow(child)
                    || orderStateJudge.isClosed(child)) {
                ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
            }
        }

        for (Order child : childOrders) {
            boolean accepted = orderStateMachineService.sendOrderEventWithDbState(
                    child, OrderEvent.APPLY_REFUND, null, OrderExceptionEnum.ORDER_REFUND_FAIL);
            if (!accepted) {
                ExceptionUtil.throwException(OrderExceptionEnum.ORDER_REFUND_FAIL);
            }
        }

        paymentRefundTransitionAppService.markParentRefunding(parentOrder.getId(),
                parentOrder.getTradeNo(), parentOrder.getBuyerId(), parentOrder.getPaymentTime());
        parentOrderRefreshAppService.refresh(parentOrderSn);

        String refundRequestNo = refundCompensationAppService.submitRefundCompensation(
                parentOrderSn,
                parentOrder.getTradeNo(),
                PaymentCompensationReasonCode.REFUND_REQUEST_CREATED
        );
        paymentCompensationTaskScheduler.executeRefundTaskIfReady(refundRequestNo);
        return refundRequestNo;
    }
}
