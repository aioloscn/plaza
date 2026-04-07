package com.aiolos.plaza.order.workflow.payment;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.MqLocalMessageType;
import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.PaymentCompensationReasonCode;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.mapper.PaymentLogMapper;
import com.aiolos.plaza.model.po.MqLocalMessage;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.model.po.PaymentLog;
import com.aiolos.plaza.mq.constant.OrderMqConstants;
import com.aiolos.plaza.order.application.payment.RefundCompensationCommandService;
import com.aiolos.plaza.order.config.OrderStateChangeInterceptor;
import com.aiolos.plaza.order.domain.outbox.MqLocalMessageFactory;
import com.aiolos.plaza.order.domain.status.OrderStatusMetadataResolver;
import com.aiolos.plaza.order.domain.status.ParentStatusDomainService;
import com.aiolos.plaza.service.MqLocalMessageService;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class PaymentNotifySupport {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ParentOrderMapper parentOrderMapper;

    @Autowired
    private PaymentLogMapper paymentLogMapper;

    @Autowired
    private MqLocalMessageService mqLocalMessageService;

    @Autowired
    private StateMachineFactory<OrderState, OrderEvent> orderStateMachineFactory;

    @Autowired
    private OrderStateChangeInterceptor orderStateChangeInterceptor;

    @Autowired
    private MqLocalMessageFactory mqLocalMessageFactory;

    @Autowired
    private OrderStatusMetadataResolver orderStatusMetadataResolver;

    @Autowired
    private ParentStatusDomainService parentStatusDomainService;

    @Autowired
    private RefundCompensationCommandService refundCompensationCommandService;

    public PaymentNotifyPrecheck inspectChildOrders(String parentOrderSn) {
        // 支付回调进入真正流转前，先判断是否已有子单被关闭/正处于 closing，决定后续走恢复还是直接退款
        boolean hasClosedChild = false;
        boolean hasClosingChild = false;
        for (Order child : listChildOrders(parentOrderSn)) {
            if (OrderState.CLOSED.getCode().equals(child.getStatus())) {
                hasClosedChild = true;
                break;
            }
            if (OrderState.CLOSING.getCode().equals(child.getStatus())) {
                hasClosingChild = true;
            }
        }
        return new PaymentNotifyPrecheck(hasClosedChild, hasClosingChild);
    }

    public String handleRefundFlowNotify(PaymentNotifyContext context) {
        ParentOrder parentOrder = context.parentOrder();
        PaymentNotifyCommand command = context.command();
        // 已经进入退款链路时，回调只补齐支付流水，不再重复推进支付成功逻辑
        savePaymentLogIfAbsent(command.outTradeNo(), parentOrder.getPayType(), command.tradeNo(), command.buyerId(),
                parentOrder.getPayAmount(), LocalDateTime.now());
        return "success";
    }

    public String handleClosedParentNotify(PaymentNotifyContext context) {
        ParentOrder parentOrder = context.parentOrder();
        PaymentNotifyCommand command = context.command();
        LocalDateTime now = LocalDateTime.now();
        savePaymentLogIfAbsent(command.outTradeNo(), parentOrder.getPayType(), command.tradeNo(), command.buyerId(),
                parentOrder.getPayAmount(), now);
        LambdaUpdateWrapper<ParentOrder> updateWrapper = orderStatusMetadataResolver.applyToParentUpdate(
                        new LambdaUpdateWrapper<ParentOrder>(),
                        OrderState.REFUNDING.getCode()
                )
                .set(ParentOrder::getUpdateTime, now)
                .eq(ParentOrder::getId, parentOrder.getId());
        if (!StringUtils.hasText(parentOrder.getTradeNo()) && StringUtils.hasText(command.tradeNo())) {
            updateWrapper.set(ParentOrder::getTradeNo, command.tradeNo())
                    .set(ParentOrder::getBuyerId, command.buyerId())
                    .set(ParentOrder::getPaymentTime, now);
        } else {
            updateWrapper.eq(ParentOrder::getStatus, OrderState.CLOSED.getCode());
        }
        parentOrderMapper.update(null, updateWrapper);
        markChildrenRefunding(command.outTradeNo(), "支付回调到达时父订单已关闭");
        refundCompensationCommandService.submitRefundCompensation(command.outTradeNo(), command.tradeNo(),
                PaymentCompensationReasonCode.PAYMENT_CALLBACK_PARENT_CLOSED);
        log.error("支付回调到达时父订单已关闭，直接进入退款中，parentOrderSn={}, tradeNo={}", command.outTradeNo(), command.tradeNo());
        return "success";
    }

    public String handleTradeNoConflict(PaymentNotifyContext context) {
        ParentOrder parentOrder = context.parentOrder();
        PaymentNotifyCommand command = context.command();
        log.error("支付回调 tradeNo 不一致，parentOrderSn={}, dbTradeNo={}, callbackTradeNo={}",
                command.outTradeNo(), parentOrder.getTradeNo(), command.tradeNo());
        return "fail";
    }

    public String handleAlreadyPaidNotify(PaymentNotifyContext context) {
        log.info("订单已处于支付后状态，幂等忽略回调: {}", context.command().outTradeNo());
        return "success";
    }

    public String handleIllegalParentStatusNotify(PaymentNotifyContext context) {
        log.warn("订单非待支付状态，拒绝执行支付状态变更，parentOrderSn={}, status={}",
                context.command().outTradeNo(), context.parentOrder().getStatus());
        return "success";
    }

    public String handleClosedChildNotify(PaymentNotifyContext context) {
        ParentOrder parentOrder = context.parentOrder();
        PaymentNotifyCommand command = context.command();
        LocalDateTime now = LocalDateTime.now();
        savePaymentLogIfAbsent(command.outTradeNo(), parentOrder.getPayType(), command.tradeNo(), command.buyerId(),
                parentOrder.getPayAmount(), now);
        parentOrderMapper.update(null, orderStatusMetadataResolver.applyToParentUpdate(
                        new LambdaUpdateWrapper<ParentOrder>(),
                        OrderState.REFUNDING.getCode()
                )
                .set(ParentOrder::getTradeNo, command.tradeNo())
                .set(ParentOrder::getBuyerId, command.buyerId())
                .set(ParentOrder::getPaymentTime, now)
                .set(ParentOrder::getUpdateTime, now)
                .eq(ParentOrder::getId, parentOrder.getId())
                .in(ParentOrder::getStatus, Arrays.asList(OrderState.CREATED.getCode(), OrderState.PAYING.getCode(), OrderState.CLOSING.getCode())));
        markChildrenRefunding(command.outTradeNo(), "支付回调时存在已关闭子单");
        refundCompensationCommandService.submitRefundCompensation(command.outTradeNo(), command.tradeNo(),
                PaymentCompensationReasonCode.PAYMENT_CALLBACK_CHILD_CLOSED);
        log.error("支付回调到达时存在已关闭子订单，直接进入退款中，parentOrderSn={}, tradeNo={}", command.outTradeNo(), command.tradeNo());
        return "success";
    }

    public String handleNormalPayNotify(PaymentNotifyContext context) {
        ParentPaymentAdvanceResult advanceResult = advanceParentOrderAfterPay(
                context.parentOrder(), context.command(), context.precheck().hasClosingChild());
        if (!advanceResult.proceed()) {
            return advanceResult.response();
        }
        ParentOrder latestParentOrder = advanceResult.parentOrder();
        PaymentNotifyCommand command = context.command();
        boolean recoverFailed = processChildOrdersAfterPay(command, latestParentOrder);
        if (recoverFailed) {
            markParentRefunding(latestParentOrder.getId(), command.tradeNo(), command.buyerId(), latestParentOrder.getPaymentTime());
            markChildrenRefunding(command.outTradeNo(), "支付补偿失败");
            refundCompensationCommandService.submitRefundCompensation(command.outTradeNo(), command.tradeNo(),
                    PaymentCompensationReasonCode.PAYMENT_RECOVER_FAIL);
            log.error("支付补偿失败，父订单进入退款中，parentOrderSn={}, tradeNo={}", command.outTradeNo(), command.tradeNo());
            return "success";
        }
        recomputeParentOrderStatus(command.outTradeNo());
        savePaidMessageIfAbsent(command.outTradeNo(), latestParentOrder);
        return "success";
    }

    public ParentPaymentAdvanceResult advanceParentOrderAfterPay(ParentOrder parentOrder,
                                                                 PaymentNotifyCommand command,
                                                                 boolean hasClosingChild) {
        LocalDateTime now = LocalDateTime.now();
        // 父单推进使用 CAS，确保并发回调下只有一个线程真正完成“待支付 -> 已支付/补偿中”切换
        int parentUpdated = parentOrderMapper.update(null, orderStatusMetadataResolver.applyToParentUpdate(
                        new LambdaUpdateWrapper<ParentOrder>(),
                        hasClosingChild ? OrderState.PAY_RECOVERING.getCode() : OrderState.PAID.getCode()
                )
                .set(ParentOrder::getPaymentTime, now)
                .set(ParentOrder::getTradeNo, command.tradeNo())
                .set(ParentOrder::getBuyerId, command.buyerId())
                .set(ParentOrder::getUpdateTime, now)
                .eq(ParentOrder::getParentOrderSn, command.outTradeNo())
                .in(ParentOrder::getStatus, Arrays.asList(OrderState.CREATED.getCode(), OrderState.PAYING.getCode(), OrderState.CLOSING.getCode())));
        if (parentUpdated == 0) {
            ParentOrder latestParentOrder = loadParentOrder(command.outTradeNo());
            if (latestParentOrder != null && isPaidOrAfter(latestParentOrder.getStatus())) {
                log.info("并发支付回调已处理，幂等返回 success: {}", command.outTradeNo());
                return ParentPaymentAdvanceResult.stop("success");
            }
            log.error("支付回调 CAS 更新父订单失败，parentOrderSn={}", command.outTradeNo());
            return ParentPaymentAdvanceResult.stop("fail");
        }
        ParentOrder latestParentOrder = loadParentOrder(command.outTradeNo());
        if (latestParentOrder == null) {
            log.error("支付回调更新成功后重新加载父订单失败，parentOrderSn={}", command.outTradeNo());
            return ParentPaymentAdvanceResult.stop("fail");
        }
        return ParentPaymentAdvanceResult.proceed(latestParentOrder);
    }

    public boolean processChildOrdersAfterPay(PaymentNotifyCommand command, ParentOrder parentOrder) {
        boolean recoverFailed = false;
        for (Order child : listChildOrders(command.outTradeNo())) {
            if (processSingleChildOrderAfterPay(child, command, parentOrder)) {
                recoverFailed = true;
            }
        }
        return recoverFailed;
    }

    public boolean sendOrderEventWithDbState(Order order,
                                             OrderEvent event,
                                             LocalDateTime paymentTime,
                                             OrderExceptionEnum errorEnum) {
        // 状态机事件始终基于数据库最新状态重放，避免使用调用方手里的旧快照导致状态跳变错误
        Order latest = orderMapper.selectById(order.getId());
        if (latest == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        StateMachine<OrderState, OrderEvent> stateMachine = orderStateMachineFactory.getStateMachine(latest.getId().toString());
        stateMachine.getStateMachineAccessor().doWithAllRegions(access -> access.addStateMachineInterceptor(orderStateChangeInterceptor));
        stateMachine.stop();
        stateMachine.getStateMachineAccessor().doWithAllRegions(access ->
                access.resetStateMachine(new DefaultStateMachineContext<>(toOrderState(latest.getStatus()), null, null, null)));
        stateMachine.start();

        MessageBuilder<OrderEvent> builder = MessageBuilder.withPayload(event).setHeader("orderId", latest.getId());
        if (paymentTime != null) {
            builder.setHeader("paymentTime", paymentTime);
        }
        boolean accepted = stateMachine.sendEvent(builder.build());
        if (stateMachine.hasStateMachineError()) {
            log.error("状态机执行异常，订单ID: {}, event={}", latest.getId(), event);
            ExceptionUtil.throwException(errorEnum);
        }
        return accepted;
    }

    public void recomputeParentOrderStatus(String parentOrderSn) {
        parentStatusDomainService.recomputeParentOrderStatus(parentOrderSn);
    }

    public void markChildrenRefunding(String parentOrderSn, String reason) {
        if (!StringUtils.hasText(parentOrderSn)) {
            return;
        }
        List<Order> childOrders = listChildOrders(parentOrderSn);
        if (childOrders.isEmpty()) {
            return;
        }
        // 已经到退款终态或履约终态的子单不再回写，避免补偿任务把正常业务结果覆盖掉
        for (Order child : childOrders) {
            if (child == null || OrderState.REFUNDING.getCode().equals(child.getStatus())
                    || OrderState.REFUNDED.getCode().equals(child.getStatus())
                    || OrderState.REFUND_FAILED.getCode().equals(child.getStatus())
                    || OrderState.DELIVERED.getCode().equals(child.getStatus())
                    || OrderState.COMPLETED.getCode().equals(child.getStatus())) {
                continue;
            }
            if (OrderState.PAY_RECOVERING.getCode().equals(child.getStatus())) {
                markChildRefunding(child.getId(), reason);
                continue;
            }
            orderMapper.update(null, orderStatusMetadataResolver.applyToOrderUpdate(
                            new LambdaUpdateWrapper<Order>(),
                            OrderState.REFUNDING.getCode()
                    )
                    .set(Order::getUpdateTime, LocalDateTime.now())
                    .eq(Order::getId, child.getId())
                    .in(Order::getStatus, Arrays.asList(
                            OrderState.CLOSED.getCode(),
                            OrderState.CREATED.getCode(),
                            OrderState.PAYING.getCode(),
                            OrderState.CLOSING.getCode(),
                            OrderState.PAID.getCode()
                    )));
        }
    }

    public void markParentRefunding(Long parentOrderId, String tradeNo, String buyerId, LocalDateTime paymentTime) {
        if (parentOrderId == null) {
            return;
        }
        parentOrderMapper.update(null, orderStatusMetadataResolver.applyToParentUpdate(
                        new LambdaUpdateWrapper<ParentOrder>(),
                        OrderState.REFUNDING.getCode()
                )
                .set(StringUtils.hasText(tradeNo), ParentOrder::getTradeNo, tradeNo)
                .set(StringUtils.hasText(buyerId), ParentOrder::getBuyerId, buyerId)
                .set(paymentTime != null, ParentOrder::getPaymentTime, paymentTime)
                .set(ParentOrder::getUpdateTime, LocalDateTime.now())
                .eq(ParentOrder::getId, parentOrderId));
    }

    public boolean isPaidOrAfter(Integer status) {
        return OrderState.PAID.getCode().equals(status)
                || OrderState.DELIVERED.getCode().equals(status)
                || OrderState.COMPLETED.getCode().equals(status)
                || OrderState.REFUNDED.getCode().equals(status);
    }

    public boolean isRefundFlowStatus(Integer status) {
        return OrderState.PAY_RECOVERING.getCode().equals(status)
                || OrderState.REFUNDING.getCode().equals(status)
                || OrderState.REFUNDED.getCode().equals(status)
                || OrderState.REFUND_FAILED.getCode().equals(status);
    }

    public boolean isParentPayableForNotify(Integer status) {
        return OrderState.CREATED.getCode().equals(status)
                || OrderState.PAYING.getCode().equals(status)
                || OrderState.CLOSING.getCode().equals(status);
    }

    public boolean isClosedStatus(Integer status) {
        return OrderState.CLOSED.getCode().equals(status);
    }

    public boolean hasTradeNoConflict(ParentOrder parentOrder, String tradeNo) {
        return StringUtils.hasText(parentOrder.getTradeNo())
                && StringUtils.hasText(tradeNo)
                && !parentOrder.getTradeNo().equals(tradeNo);
    }

    private void savePaymentLogIfAbsent(String parentOrderSn,
                                        Integer payType,
                                        String tradeNo,
                                        String buyerId,
                                        BigDecimal payAmount,
                                        LocalDateTime paymentTime) {
        if (!StringUtils.hasText(parentOrderSn) || !StringUtils.hasText(tradeNo)) {
            return;
        }
        long exists = paymentLogMapper.selectCount(new LambdaQueryWrapper<PaymentLog>()
                .eq(PaymentLog::getOrderSn, parentOrderSn)
                .eq(PaymentLog::getTradeNo, tradeNo));
        if (exists > 0) {
            return;
        }
        PaymentLog paymentLog = new PaymentLog();
        paymentLog.setOrderSn(parentOrderSn);
        paymentLog.setPayType(payType);
        paymentLog.setTradeNo(tradeNo);
        paymentLog.setBuyerId(buyerId);
        paymentLog.setTotalAmount(payAmount);
        paymentLog.setPaymentTime(paymentTime);
        paymentLog.setCreateTime(LocalDateTime.now());
        paymentLogMapper.insert(paymentLog);
    }

    private void savePaidMessageIfAbsent(String parentOrderSn, ParentOrder parentOrder) {
        long msgExists = mqLocalMessageService.count(new LambdaQueryWrapper<MqLocalMessage>()
                .eq(MqLocalMessage::getTopic, OrderMqConstants.BINDING_ORDER_PAID_OUT)
                .eq(MqLocalMessage::getBusinessKey, "order-paid:" + parentOrderSn));
        if (msgExists > 0) {
            log.info("支付成功本地消息已存在，跳过重复写入: {}", parentOrderSn);
            return;
        }
        MqLocalMessage localMsg = mqLocalMessageFactory.build(
                OrderMqConstants.BINDING_ORDER_PAID_OUT,
                MqLocalMessageType.ORDER_PAID,
                "order-paid:" + parentOrderSn,
                JSON.toJSONString(parentOrder)
        );
        mqLocalMessageService.save(localMsg);
    }

    private boolean processSingleChildOrderAfterPay(Order child, PaymentNotifyCommand command, ParentOrder parentOrder) {
        if (isPaidOrAfter(child.getStatus())) {
            return false;
        }
        if (isCreatedOrPaying(child.getStatus())) {
            sendRequiredOrderEvent(child, OrderEvent.PAY, parentOrder.getPaymentTime(),
                    OrderExceptionEnum.ORDER_STATUS_ERROR, "支付");
            return false;
        }
        if (OrderState.CLOSING.getCode().equals(child.getStatus())) {
            return handleClosingChildAfterPay(child, command, parentOrder);
        }
        if (OrderState.PAY_RECOVERING.getCode().equals(child.getStatus())) {
            log.error("支付补偿失败：回调重试时子单仍处于补偿中，orderId={}, parentOrderSn={}, childStatus={}",
                    child.getId(), command.outTradeNo(), child.getStatus());
            markChildRefunding(child.getId(), "回调重试时仍处于补偿中");
            return true;
        }
        log.error("子订单状态非法，拒绝处理支付回调，orderId={}, status={}", child.getId(), child.getStatus());
        ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        return false;
    }

    private boolean handleClosingChildAfterPay(Order child, PaymentNotifyCommand command, ParentOrder parentOrder) {
        // closing 子单先接收 PAY_CALLBACK 进入补偿态，再尝试 RECOVER_SUCCESS 恢复成 paid。
        sendRequiredOrderEvent(child, OrderEvent.PAY_CALLBACK, parentOrder.getPaymentTime(),
                OrderExceptionEnum.ORDER_STATUS_ERROR, "支付回调");
        try {
            boolean recoverAccepted = sendOrderEventWithDbState(child, OrderEvent.RECOVER_SUCCESS,
                    parentOrder.getPaymentTime(), OrderExceptionEnum.ORDER_STATUS_ERROR);
            if (!recoverAccepted) {
                log.error("支付补偿失败：状态机拒绝补偿成功事件，orderId={}, parentOrderSn={}, childStatus={}",
                        child.getId(), command.outTradeNo(), child.getStatus());
                markChildRefunding(child.getId(), "状态机拒绝补偿成功事件");
                return true;
            }
            return false;
        } catch (Exception recoverEx) {
            log.error("支付补偿失败：补偿成功事件执行异常，orderId={}, parentOrderSn={}, childStatus={}",
                    child.getId(), command.outTradeNo(), child.getStatus(), recoverEx);
            markChildRefunding(child.getId(), recoverEx.getMessage());
            return true;
        }
    }

    private void sendRequiredOrderEvent(Order child,
                                        OrderEvent event,
                                        LocalDateTime paymentTime,
                                        OrderExceptionEnum errorEnum,
                                        String eventDesc) {
        boolean accepted = sendOrderEventWithDbState(child, event, paymentTime, errorEnum);
        if (!accepted) {
            log.error("订单状态机拒绝{}事件，订单ID: {}, 当前状态: {}", eventDesc, child.getId(), child.getStatus());
            ExceptionUtil.throwException(errorEnum);
        }
    }

    private void markChildRefunding(Long orderId, String reason) {
        if (orderId == null) {
            return;
        }
        log.error("执行 markChildRefunding，orderId={}, reason={}", orderId, reason);
        try {
            Order latest = orderMapper.selectById(orderId);
            if (latest != null && OrderState.PAY_RECOVERING.getCode().equals(latest.getStatus())) {
                // 先尝试通过状态机走 recover fail，只有失败时才用兜底 SQL 回写退款中。
                sendOrderEventWithDbState(latest, OrderEvent.RECOVER_FAIL, null, OrderExceptionEnum.ORDER_STATUS_ERROR);
            }
        } catch (Exception ignore) {
            log.warn("订单补偿失败事件流转异常，orderId={}, reason={}", orderId, reason);
        }
        orderMapper.update(null, orderStatusMetadataResolver.applyToOrderUpdate(
                        new LambdaUpdateWrapper<Order>(),
                        OrderState.REFUNDING.getCode()
                )
                .set(Order::getUpdateTime, LocalDateTime.now())
                .eq(Order::getId, orderId)
                .in(Order::getStatus, Arrays.asList(OrderState.CLOSING.getCode(), OrderState.PAY_RECOVERING.getCode())));
    }

    private ParentOrder loadParentOrder(String parentOrderSn) {
        return parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, parentOrderSn));
    }

    private List<Order> listChildOrders(String parentOrderSn) {
        List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, parentOrderSn));
        return childOrders == null ? Collections.emptyList() : childOrders;
    }

    private boolean isCreatedOrPaying(Integer status) {
        return OrderState.CREATED.getCode().equals(status) || OrderState.PAYING.getCode().equals(status);
    }

    private OrderState toOrderState(Integer statusCode) {
        for (OrderState value : OrderState.values()) {
            if (value.getCode().equals(statusCode)) {
                return value;
            }
        }
        log.error("未知订单状态编码，statusCode={}", statusCode);
        ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        return OrderState.INVALID;
    }
}
