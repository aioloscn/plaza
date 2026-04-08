package com.aiolos.plaza.order.application.payment.notify;

import com.aiolos.plaza.enums.MqLocalMessageType;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.PaymentCompensationReasonCode;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.mapper.PaymentLogMapper;
import com.aiolos.plaza.model.po.MqLocalMessage;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.model.po.PaymentLog;
import com.aiolos.plaza.mq.constant.OrderMqConstants;
import com.aiolos.plaza.order.application.order.status.ParentOrderRefreshAppService;
import com.aiolos.plaza.order.application.payment.compensation.RefundCompensationAppService;
import com.aiolos.plaza.order.application.payment.notify.model.ParentPaymentAdvanceResult;
import com.aiolos.plaza.order.application.payment.notify.model.PaymentResultPrecheck;
import com.aiolos.plaza.order.application.payment.notify.model.PaymentResultCommand;
import com.aiolos.plaza.order.application.payment.notify.model.PaymentResultContext;
import com.aiolos.plaza.order.domain.outbox.MqLocalMessageFactory;
import com.aiolos.plaza.order.domain.order.status.OrderStatusMetadataResolver;
import com.aiolos.plaza.service.MqLocalMessageService;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class PaymentNotifyOrchestrator {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ParentOrderMapper parentOrderMapper;

    @Autowired
    private PaymentLogMapper paymentLogMapper;

    @Autowired
    private MqLocalMessageService mqLocalMessageService;

    @Autowired
    private MqLocalMessageFactory mqLocalMessageFactory;

    @Autowired
    private OrderStatusMetadataResolver orderStatusMetadataResolver;

    @Autowired
    private ParentOrderRefreshAppService parentOrderRefreshAppService;

    @Autowired
    private RefundCompensationAppService refundCompensationAppService;

    @Autowired
    private ParentPaymentAdvanceAppService parentPaymentAdvanceAppService;

    @Autowired
    private ChildPaymentRecoveryAppService childPaymentRecoveryAppService;

    @Autowired
    private PaymentRefundTransitionAppService paymentRefundTransitionAppService;

    public PaymentResultPrecheck inspectChildOrders(String parentOrderSn) {
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
        return new PaymentResultPrecheck(hasClosedChild, hasClosingChild);
    }

    public String handleRefundFlowNotify(PaymentResultContext context) {
        ParentOrder parentOrder = context.parentOrder();
        PaymentResultCommand command = context.command();
        // 已经进入退款链路时，回调只补齐支付流水，不再重复推进支付成功逻辑
        savePaymentLogIfAbsent(command.outTradeNo(), parentOrder.getPayType(), command.tradeNo(), command.buyerId(),
                parentOrder.getPayAmount(), LocalDateTime.now());
        return "success";
    }

    public String handleClosedParentNotify(PaymentResultContext context) {
        ParentOrder parentOrder = context.parentOrder();
        PaymentResultCommand command = context.command();
        LocalDateTime now = LocalDateTime.now();
        // 父单已关闭但支付成功回调晚到时，需要先补支付流水，随后立即转入退款中
        savePaymentLogIfAbsent(command.outTradeNo(), parentOrder.getPayType(), command.tradeNo(), command.buyerId(),
                parentOrder.getPayAmount(), now);
        LambdaUpdateWrapper<ParentOrder> updateWrapper = orderStatusMetadataResolver.applyToParentUpdate(
                        new LambdaUpdateWrapper<ParentOrder>(),
                        OrderState.REFUNDING.getCode()
                )
                .set(ParentOrder::getUpdateTime, now)
                .eq(ParentOrder::getId, parentOrder.getId());
        if (!StringUtils.hasText(parentOrder.getTradeNo()) && StringUtils.hasText(command.tradeNo())) {
            // 数据库还没记下交易号时一并补齐，避免后续退款缺少支付凭据
            updateWrapper.set(ParentOrder::getTradeNo, command.tradeNo())
                    .set(ParentOrder::getBuyerId, command.buyerId())
                    .set(ParentOrder::getPaymentTime, now);
        } else {
            // 已有交易号时要求状态仍为 CLOSED，避免覆盖其它并发流转
            updateWrapper.eq(ParentOrder::getStatus, OrderState.CLOSED.getCode());
        }
        parentOrderMapper.update(null, updateWrapper);
        paymentRefundTransitionAppService.markChildrenRefunding(command.outTradeNo(), "支付回调到达时父订单已关闭");
        refundCompensationAppService.submitRefundCompensation(command.outTradeNo(), command.tradeNo(),
                PaymentCompensationReasonCode.PAYMENT_CALLBACK_PARENT_CLOSED);
        log.error("支付回调到达时父订单已关闭，直接进入退款中，parentOrderSn={}, tradeNo={}", command.outTradeNo(), command.tradeNo());
        return "success";
    }

    public String handleTradeNoConflict(PaymentResultContext context) {
        ParentOrder parentOrder = context.parentOrder();
        PaymentResultCommand command = context.command();
        // 同一父单出现不同 tradeNo 时按异常处理，避免把别的支付单据误记到当前订单
        log.error("支付回调 tradeNo 不一致，parentOrderSn={}, dbTradeNo={}, callbackTradeNo={}",
                command.outTradeNo(), parentOrder.getTradeNo(), command.tradeNo());
        return "fail";
    }

    public String handleAlreadyPaidNotify(PaymentResultContext context) {
        log.info("订单已处于支付后状态，幂等忽略回调: {}", context.command().outTradeNo());
        return "success";
    }

    public String handleIllegalParentStatusNotify(PaymentResultContext context) {
        log.warn("订单非待支付状态，拒绝执行支付状态变更，parentOrderSn={}, status={}",
                context.command().outTradeNo(), context.parentOrder().getStatus());
        return "success";
    }

    public String handleClosedChildNotify(PaymentResultContext context) {
        ParentOrder parentOrder = context.parentOrder();
        PaymentResultCommand command = context.command();
        LocalDateTime now = LocalDateTime.now();
        // 有子单已关闭时不能再按正常支付成功推进，直接整体切到退款链路
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
        paymentRefundTransitionAppService.markChildrenRefunding(command.outTradeNo(), "支付回调时存在已关闭子单");
        refundCompensationAppService.submitRefundCompensation(command.outTradeNo(), command.tradeNo(),
                PaymentCompensationReasonCode.PAYMENT_CALLBACK_CHILD_CLOSED);
        log.error("支付回调到达时存在已关闭子订单，直接进入退款中，parentOrderSn={}, tradeNo={}", command.outTradeNo(), command.tradeNo());
        return "success";
    }

    public String handleNormalPayNotify(PaymentResultContext context) {
        // 先推进父单，再继续处理子单恢复和后续消息
        ParentPaymentAdvanceResult advanceResult = parentPaymentAdvanceAppService.advanceAfterPay(
                context.command(), context.precheck().hasClosingChild());
        if (!advanceResult.proceed()) {
            return advanceResult.response();
        }
        ParentOrder latestParentOrder = advanceResult.parentOrder();
        PaymentResultCommand command = context.command();
        // 返回值大于 0 说明存在 closing 子单被恢复，当前链路把它视为需要转退款补偿的恢复失败信号
        boolean recoverFailed = childPaymentRecoveryAppService.processAfterPay(command, latestParentOrder) > 0;
        if (recoverFailed) {
            paymentRefundTransitionAppService.markParentRefunding(latestParentOrder.getId(), command.tradeNo(), command.buyerId(), latestParentOrder.getPaymentTime());
            paymentRefundTransitionAppService.markChildrenRefunding(command.outTradeNo(), "支付补偿失败");
            refundCompensationAppService.submitRefundCompensation(command.outTradeNo(), command.tradeNo(),
                    PaymentCompensationReasonCode.PAYMENT_RECOVER_FAIL);
            log.error("支付补偿失败，父订单进入退款中，parentOrderSn={}, tradeNo={}", command.outTradeNo(), command.tradeNo());
            return "success";
        }
        recomputeParentOrderStatus(command.outTradeNo());
        savePaidMessageIfAbsent(command.outTradeNo(), latestParentOrder);
        return "success";
    }

    public void recomputeParentOrderStatus(String parentOrderSn) {
        // 子单都收敛后重新聚合父单展示态与三维状态
        parentOrderRefreshAppService.refresh(parentOrderSn);
    }

    public boolean hasTradeNoConflict(ParentOrder parentOrder, String tradeNo) {
        // 只有数据库和回调都带 tradeNo 且两者不一致时才视为冲突
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
        // 支付流水按 parentOrderSn + tradeNo 幂等写入
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
        // 支付成功 outbox 也做幂等保护，避免重复投递支付成功消息
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

    private List<Order> listChildOrders(String parentOrderSn) {
        // 子单查询统一收口在这里，顺便兜底空集合
        List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, parentOrderSn));
        return childOrders == null ? Collections.emptyList() : childOrders;
    }
}
