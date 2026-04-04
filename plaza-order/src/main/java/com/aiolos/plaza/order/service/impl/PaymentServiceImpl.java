package com.aiolos.plaza.order.service.impl;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.mapper.PaymentLogMapper;
import com.aiolos.plaza.model.po.MqLocalMessage;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.model.po.PaymentLog;
import com.aiolos.plaza.mq.constant.OrderMqConstants;
import com.aiolos.plaza.order.config.AlipayConfig;
import com.aiolos.plaza.order.config.OrderStateChangeInterceptor;
import com.aiolos.plaza.order.coreflow.inventory.service.OrderInventoryService;
import com.aiolos.plaza.order.service.PaymentService;
import com.aiolos.plaza.service.MqLocalMessageService;
import com.alibaba.fastjson.JSON;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeWapPayModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PaymentServiceImpl implements PaymentService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ParentOrderMapper parentOrderMapper;

    @Autowired
    private PaymentLogMapper paymentLogMapper;

    @Autowired
    private OrderInventoryService orderInventoryService;

    @Autowired
    private MqLocalMessageService mqLocalMessageService;

    @Autowired
    private AlipayConfig alipayConfig;

    @Autowired
    private StateMachineFactory<OrderState, OrderEvent> orderStateMachineFactory;

    @Autowired
    private OrderStateChangeInterceptor orderStateChangeInterceptor;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String pay(Long userId, String orderSn, Integer payType, boolean isMobile) {
        // 支付入口只负责支付域逻辑，订单创建与购物车流程仍在 PlazaOrderService。
        ParentOrder parentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, orderSn)
                .eq(ParentOrder::getUserId, userId));
        if (parentOrder == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }

        if (isPaidOrAfter(parentOrder.getStatus()) || OrderState.CLOSED.getCode().equals(parentOrder.getStatus())) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }
        if (OrderState.PAY_RECOVERING.getCode().equals(parentOrder.getStatus())
                || OrderState.REFUNDING.getCode().equals(parentOrder.getStatus())
                || OrderState.REFUNDED.getCode().equals(parentOrder.getStatus())
                || OrderState.REFUND_FAILED.getCode().equals(parentOrder.getStatus())) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }
        if (!OrderState.CREATED.getCode().equals(parentOrder.getStatus())
                && !OrderState.PAYING.getCode().equals(parentOrder.getStatus())
                && !OrderState.CLOSING.getCode().equals(parentOrder.getStatus())) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }

        // 进入支付中，并延长预占，降低“支付过程中刚好过期关单”的概率。
        LocalDateTime now = LocalDateTime.now();
        parentOrderMapper.update(null, new LambdaUpdateWrapper<ParentOrder>()
                .set(ParentOrder::getStatus, OrderState.PAYING.getCode())
                .set(ParentOrder::getUpdateTime, now)
                .eq(ParentOrder::getId, parentOrder.getId())
                .in(ParentOrder::getStatus, Arrays.asList(OrderState.CREATED.getCode(), OrderState.CLOSING.getCode())));
        orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .set(Order::getStatus, OrderState.PAYING.getCode())
                .set(Order::getUpdateTime, now)
                .eq(Order::getParentOrderSn, orderSn)
                .in(Order::getStatus, Arrays.asList(OrderState.CREATED.getCode(), OrderState.CLOSING.getCode())));
        List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, orderSn));
        if (childOrders != null) {
            for (Order child : childOrders) {
                if (StringUtils.hasText(child.getReservationNo())) {
                    orderInventoryService.extendExpireTime(child.getReservationNo(), now.plusMinutes(2));
                }
            }
        }
        recomputeParentOrderStatus(orderSn);

        try {
            AlipayClient alipayClient = new DefaultAlipayClient(
                    alipayConfig.getGatewayUrl(),
                    alipayConfig.getAppId(),
                    alipayConfig.getMerchantPrivateKey(),
                    alipayConfig.getFormat(),
                    alipayConfig.getCharset(),
                    alipayConfig.getAlipayPublicKey(),
                    alipayConfig.getSignType());

            if (isMobile) {
                AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
                AlipayTradeWapPayModel model = new AlipayTradeWapPayModel();
                model.setOutTradeNo(orderSn);
                model.setSubject("Plaza商城订单-" + orderSn);
                model.setTotalAmount(parentOrder.getPayAmount().toString());
                model.setBody("Plaza商城订单支付");
                model.setProductCode("QUICK_WAP_WAY");
                request.setBizModel(model);
                request.setNotifyUrl(alipayConfig.getNotifyUrl());
                request.setReturnUrl(alipayConfig.getReturnUrl());
                return alipayClient.pageExecute(request).getBody();
            } else {
                AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
                AlipayTradePagePayModel model = new AlipayTradePagePayModel();
                model.setOutTradeNo(orderSn);
                model.setSubject("Plaza商城订单-" + orderSn);
                model.setTotalAmount(parentOrder.getPayAmount().toString());
                model.setBody("Plaza商城订单支付");
                model.setProductCode("FAST_INSTANT_TRADE_PAY");
                request.setBizModel(model);
                request.setNotifyUrl(alipayConfig.getNotifyUrl());
                request.setReturnUrl(alipayConfig.getReturnUrl());
                return alipayClient.pageExecute(request).getBody();
            }
        } catch (Exception e) {
            log.error("生成支付表单失败", e);
            ExceptionUtil.throwException(OrderExceptionEnum.CREATE_PAY_FORM_FAIL);
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String payNotify(Map<String, String> params) {
        log.info("收到支付宝回调通知: {}", params);
        try {
            boolean signVerified = true;
            try {
                signVerified = AlipaySignature.rsaCheckV1(
                        params,
                        alipayConfig.getAlipayPublicKey(),
                        alipayConfig.getCharset(),
                        alipayConfig.getSignType());
            } catch (Exception e) {
                log.error("支付宝验签异常", e);
                return "fail";
            }
            if (!signVerified) {
                log.error("支付宝回调验签失败");
                return "fail";
            }

            String outTradeNo = params.get("out_trade_no");
            String tradeStatus = params.get("trade_status");
            String totalAmount = params.get("total_amount");
            String appId = params.get("app_id");
            String tradeNo = params.get("trade_no");
            String buyerId = params.get("buyer_id");

            if (!StringUtils.hasText(outTradeNo) || !StringUtils.hasText(totalAmount) || !StringUtils.hasText(appId)) {
                log.error("支付宝回调缺少关键参数: out_trade_no={}, total_amount={}, app_id={}", outTradeNo, totalAmount, appId);
                return "fail";
            }
            if (!alipayConfig.getAppId().equals(appId)) {
                log.error("支付宝回调AppID不匹配");
                return "fail";
            }
            if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
                return "success";
            }

            ParentOrder parentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                    .eq(ParentOrder::getParentOrderSn, outTradeNo));
            if (parentOrder == null) {
                log.error("支付宝回调订单不存在: {}", outTradeNo);
                return "fail";
            }
            if (parentOrder.getPayAmount().compareTo(new BigDecimal(totalAmount)) != 0) {
                log.error("支付宝回调金额不匹配: 订单金额={}, 回调金额={}", parentOrder.getPayAmount(), totalAmount);
                return "fail";
            }

            if (OrderState.PAY_RECOVERING.getCode().equals(parentOrder.getStatus())
                    || OrderState.REFUNDING.getCode().equals(parentOrder.getStatus())
                    || OrderState.REFUNDED.getCode().equals(parentOrder.getStatus())
                    || OrderState.REFUND_FAILED.getCode().equals(parentOrder.getStatus())) {
                savePaymentLogIfAbsent(outTradeNo, parentOrder.getPayType(), tradeNo, buyerId, parentOrder.getPayAmount(), LocalDateTime.now());
                return "success";
            }
            if (OrderState.CLOSED.getCode().equals(parentOrder.getStatus())) {
                LocalDateTime now = LocalDateTime.now();
                savePaymentLogIfAbsent(outTradeNo, parentOrder.getPayType(), tradeNo, buyerId, parentOrder.getPayAmount(), now);
                if (!StringUtils.hasText(parentOrder.getTradeNo()) && StringUtils.hasText(tradeNo)) {
                    parentOrderMapper.update(null, new LambdaUpdateWrapper<ParentOrder>()
                            .set(ParentOrder::getStatus, OrderState.REFUNDING.getCode())
                            .set(ParentOrder::getTradeNo, tradeNo)
                            .set(ParentOrder::getBuyerId, buyerId)
                            .set(ParentOrder::getPaymentTime, now)
                            .set(ParentOrder::getUpdateTime, now)
                            .eq(ParentOrder::getId, parentOrder.getId()));
                } else {
                    parentOrderMapper.update(null, new LambdaUpdateWrapper<ParentOrder>()
                            .set(ParentOrder::getStatus, OrderState.REFUNDING.getCode())
                            .set(ParentOrder::getUpdateTime, now)
                            .eq(ParentOrder::getId, parentOrder.getId())
                            .eq(ParentOrder::getStatus, OrderState.CLOSED.getCode()));
                }
                log.error("支付回调到达时父订单已关闭，直接进入退款中，parentOrderSn={}, tradeNo={}", outTradeNo, tradeNo);
                return "success";
            }

            if (isPaidOrAfter(parentOrder.getStatus())) {
                if (StringUtils.hasText(parentOrder.getTradeNo()) && StringUtils.hasText(tradeNo) && !parentOrder.getTradeNo().equals(tradeNo)) {
                    log.error("支付回调tradeNo不一致，parentOrderSn={}, dbTradeNo={}, callbackTradeNo={}", outTradeNo, parentOrder.getTradeNo(), tradeNo);
                    return "fail";
                }
                log.info("订单已处于支付后状态，幂等忽略回调: {}", outTradeNo);
                return "success";
            }
            if (!OrderState.CREATED.getCode().equals(parentOrder.getStatus())
                    && !OrderState.PAYING.getCode().equals(parentOrder.getStatus())
                    && !OrderState.CLOSING.getCode().equals(parentOrder.getStatus())) {
                log.warn("订单非待支付状态，拒绝执行支付状态变更，parentOrderSn={}, status={}", outTradeNo, parentOrder.getStatus());
                return "success";
            }

            List<Order> childOrdersPrecheck = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                    .eq(Order::getParentOrderSn, outTradeNo));
            boolean hasClosedChild = false;
            boolean hasClosingChild = false;
            if (childOrdersPrecheck != null) {
                for (Order child : childOrdersPrecheck) {
                    if (OrderState.CLOSED.getCode().equals(child.getStatus())) {
                        hasClosedChild = true;
                        break;
                    }
                    if (OrderState.CLOSING.getCode().equals(child.getStatus())) {
                        hasClosingChild = true;
                    }
                }
            }
            if (hasClosedChild) {
                LocalDateTime now = LocalDateTime.now();
                savePaymentLogIfAbsent(outTradeNo, parentOrder.getPayType(), tradeNo, buyerId, parentOrder.getPayAmount(), now);
                parentOrderMapper.update(null, new LambdaUpdateWrapper<ParentOrder>()
                        .set(ParentOrder::getStatus, OrderState.REFUNDING.getCode())
                        .set(ParentOrder::getTradeNo, tradeNo)
                        .set(ParentOrder::getBuyerId, buyerId)
                        .set(ParentOrder::getPaymentTime, now)
                        .set(ParentOrder::getUpdateTime, now)
                        .eq(ParentOrder::getId, parentOrder.getId())
                        .in(ParentOrder::getStatus, Arrays.asList(OrderState.CREATED.getCode(), OrderState.PAYING.getCode(), OrderState.CLOSING.getCode())));
                log.error("支付回调到达时存在已关闭子订单，直接进入退款中，parentOrderSn={}, tradeNo={}", outTradeNo, tradeNo);
                return "success";
            }

            // 显式区分两条语义路径：普通支付直接到 PAID；软关单回调先进入 PAY_RECOVERING。
            LocalDateTime now = LocalDateTime.now();
            int parentUpdated = parentOrderMapper.update(null, new LambdaUpdateWrapper<ParentOrder>()
                    .set(ParentOrder::getStatus, hasClosingChild ? OrderState.PAY_RECOVERING.getCode() : OrderState.PAID.getCode())
                    .set(ParentOrder::getPaymentTime, now)
                    .set(ParentOrder::getTradeNo, tradeNo)
                    .set(ParentOrder::getBuyerId, buyerId)
                    .set(ParentOrder::getUpdateTime, now)
                    .eq(ParentOrder::getParentOrderSn, outTradeNo)
                    .in(ParentOrder::getStatus, Arrays.asList(OrderState.CREATED.getCode(), OrderState.PAYING.getCode(), OrderState.CLOSING.getCode())));
            if (parentUpdated == 0) {
                ParentOrder latestParentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                        .eq(ParentOrder::getParentOrderSn, outTradeNo));
                if (latestParentOrder != null && isPaidOrAfter(latestParentOrder.getStatus())) {
                    log.info("并发支付回调已处理，幂等返回 success: {}", outTradeNo);
                    return "success";
                }
                log.error("支付回调CAS更新父订单失败，parentOrderSn={}", outTradeNo);
                return "fail";
            }
            parentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                    .eq(ParentOrder::getParentOrderSn, outTradeNo));

            List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                    .eq(Order::getParentOrderSn, outTradeNo));
            boolean recoverFailed = false;
            for (Order child : childOrders) {
                if (isPaidOrAfter(child.getStatus())) {
                    continue;
                }
                if (OrderState.CREATED.getCode().equals(child.getStatus())
                        || OrderState.PAYING.getCode().equals(child.getStatus())) {
                    boolean accepted = sendOrderEventWithDbState(child, OrderEvent.PAY, parentOrder.getPaymentTime(), OrderExceptionEnum.ORDER_STATUS_ERROR);
                    if (!accepted) {
                        log.error("订单状态机拒绝支付事件，订单ID: {}, 当前状态: {}", child.getId(), child.getStatus());
                        ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
                    }
                } else if (OrderState.CLOSING.getCode().equals(child.getStatus())) {
                    // 明确补偿语义：先吃回调，再尝试恢复成功；失败就进入退款中。
                    boolean callbackAccepted = sendOrderEventWithDbState(child, OrderEvent.PAY_CALLBACK, parentOrder.getPaymentTime(), OrderExceptionEnum.ORDER_STATUS_ERROR);
                    if (!callbackAccepted) {
                        log.error("订单状态机拒绝支付回调事件，订单ID: {}, 当前状态: {}", child.getId(), child.getStatus());
                        ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
                    }
                    try {
                        boolean recoverAccepted = sendOrderEventWithDbState(child, OrderEvent.RECOVER_SUCCESS, parentOrder.getPaymentTime(), OrderExceptionEnum.ORDER_STATUS_ERROR);
                        if (!recoverAccepted) {
                            recoverFailed = true;
                            log.error("支付补偿失败：状态机拒绝补偿成功事件，orderId={}, parentOrderSn={}, childStatus={}",
                                    child.getId(), outTradeNo, child.getStatus());
                            markChildRefunding(child.getId(), "状态机拒绝补偿成功事件");
                        }
                    } catch (Exception recoverEx) {
                        recoverFailed = true;
                        log.error("支付补偿失败：补偿成功事件执行异常，orderId={}, parentOrderSn={}, childStatus={}",
                                child.getId(), outTradeNo, child.getStatus(), recoverEx);
                        markChildRefunding(child.getId(), recoverEx.getMessage());
                    }
                } else if (OrderState.PAY_RECOVERING.getCode().equals(child.getStatus())) {
                    recoverFailed = true;
                    log.error("支付补偿失败：回调重试时子单仍处于补偿中，orderId={}, parentOrderSn={}, childStatus={}",
                            child.getId(), outTradeNo, child.getStatus());
                    markChildRefunding(child.getId(), "回调重试时仍处于补偿中");
                } else {
                    log.error("子订单状态非法，拒绝处理支付回调，orderId={}, status={}", child.getId(), child.getStatus());
                    ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
                }
            }
            if (recoverFailed) {
                markParentRefunding(parentOrder.getId(), tradeNo, buyerId, parentOrder.getPaymentTime());
                log.error("支付补偿失败，父订单进入退款中，parentOrderSn={}, tradeNo={}", outTradeNo, tradeNo);
                return "success";
            }

            recomputeParentOrderStatus(outTradeNo);

            long msgExists = mqLocalMessageService.count(new LambdaQueryWrapper<MqLocalMessage>()
                    .eq(MqLocalMessage::getTopic, OrderMqConstants.BINDING_ORDER_PAID_OUT)
                    .eq(MqLocalMessage::getBusinessKey, outTradeNo));
            if (msgExists == 0) {
                MqLocalMessage localMsg = new MqLocalMessage();
                localMsg.setTopic(OrderMqConstants.BINDING_ORDER_PAID_OUT);
                localMsg.setContent(JSON.toJSONString(parentOrder));
                localMsg.setState(0);
                localMsg.setBusinessKey(outTradeNo);
                localMsg.setCreateTime(LocalDateTime.now());
                localMsg.setUpdateTime(LocalDateTime.now());
                mqLocalMessageService.save(localMsg);
            } else {
                log.info("支付成功本地消息已存在，跳过重复写入: {}", outTradeNo);
            }
            return "success";
        } catch (Exception e) {
            log.error("支付宝回调处理异常", e);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return "fail";
        }
    }

    private boolean isPaidOrAfter(Integer status) {
        return OrderState.PAID.getCode().equals(status)
                || OrderState.DELIVERED.getCode().equals(status)
                || OrderState.COMPLETED.getCode().equals(status)
                || OrderState.REFUNDED.getCode().equals(status);
    }

    private void savePaymentLogIfAbsent(String parentOrderSn, Integer payType, String tradeNo, String buyerId, BigDecimal payAmount, LocalDateTime paymentTime) {
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

    private boolean sendOrderEventWithDbState(Order order, OrderEvent event, LocalDateTime paymentTime, OrderExceptionEnum errorEnum) {
        // 始终以数据库最新状态作为状态机 source，避免同一事务内对象状态滞后导致事件被误拒绝。
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

    private void recomputeParentOrderStatus(String parentOrderSn) {
        if (!StringUtils.hasText(parentOrderSn)) {
            return;
        }
        ParentOrder parentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, parentOrderSn));
        if (parentOrder == null) {
            return;
        }
        List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, parentOrderSn));
        if (childOrders == null || childOrders.isEmpty()) {
            return;
        }
        Integer targetStatus = calculateParentStatus(childOrders.stream().map(Order::getStatus).collect(Collectors.toList()));
        if (targetStatus == null || Objects.equals(parentOrder.getStatus(), targetStatus)) {
            return;
        }
        parentOrderMapper.update(null, new LambdaUpdateWrapper<ParentOrder>()
                .set(ParentOrder::getStatus, targetStatus)
                .set(ParentOrder::getUpdateTime, LocalDateTime.now())
                .eq(ParentOrder::getId, parentOrder.getId())
                .eq(ParentOrder::getStatus, parentOrder.getStatus()));
    }

    private Integer calculateParentStatus(List<Integer> childStatuses) {
        if (childStatuses == null || childStatuses.isEmpty()) {
            return null;
        }
        boolean allClosed = childStatuses.stream().allMatch(s -> OrderState.CLOSED.getCode().equals(s));
        if (allClosed) {
            return OrderState.CLOSED.getCode();
        }
        boolean allCompleted = childStatuses.stream().allMatch(s -> OrderState.COMPLETED.getCode().equals(s));
        if (allCompleted) {
            return OrderState.COMPLETED.getCode();
        }
        boolean allDeliveredOrCompleted = childStatuses.stream().allMatch(s ->
                OrderState.DELIVERED.getCode().equals(s) || OrderState.COMPLETED.getCode().equals(s));
        boolean hasDelivered = childStatuses.stream().anyMatch(s -> OrderState.DELIVERED.getCode().equals(s));
        if (allDeliveredOrCompleted && hasDelivered) {
            return OrderState.DELIVERED.getCode();
        }
        boolean hasCreated = childStatuses.stream().anyMatch(s -> OrderState.CREATED.getCode().equals(s));
        boolean hasPaying = childStatuses.stream().anyMatch(s -> OrderState.PAYING.getCode().equals(s));
        boolean hasClosing = childStatuses.stream().anyMatch(s -> OrderState.CLOSING.getCode().equals(s));
        boolean hasPayRecovering = childStatuses.stream().anyMatch(s -> OrderState.PAY_RECOVERING.getCode().equals(s));
        boolean hasRefunding = childStatuses.stream().anyMatch(s -> OrderState.REFUNDING.getCode().equals(s));
        boolean hasRefundFailed = childStatuses.stream().anyMatch(s -> OrderState.REFUND_FAILED.getCode().equals(s));
        boolean allRefunded = childStatuses.stream().allMatch(s -> OrderState.REFUNDED.getCode().equals(s));
        boolean hasPaidOrAfter = childStatuses.stream().anyMatch(s ->
                OrderState.PAID.getCode().equals(s)
                        || OrderState.DELIVERED.getCode().equals(s)
                        || OrderState.COMPLETED.getCode().equals(s)
                        || OrderState.REFUNDED.getCode().equals(s));
        if (hasRefunding) {
            return OrderState.REFUNDING.getCode();
        }
        if (allRefunded) {
            return OrderState.REFUNDED.getCode();
        }
        if (hasRefundFailed) {
            return OrderState.REFUND_FAILED.getCode();
        }
        if (!hasCreated && hasPaidOrAfter) {
            return OrderState.PAID.getCode();
        }
        if (!hasPaidOrAfter && hasPayRecovering) {
            return OrderState.PAY_RECOVERING.getCode();
        }
        if (!hasPaidOrAfter && hasClosing) {
            return OrderState.CLOSING.getCode();
        }
        if (!hasPaidOrAfter && hasPaying) {
            return OrderState.PAYING.getCode();
        }
        return OrderState.CREATED.getCode();
    }

    private void markChildRefunding(Long orderId, String reason) {
        if (orderId == null) {
            return;
        }
        log.error("执行 markChildRefunding，orderId={}, reason={}", orderId, reason);
        try {
            Order latest = orderMapper.selectById(orderId);
            if (latest != null && OrderState.PAY_RECOVERING.getCode().equals(latest.getStatus())) {
                sendOrderEventWithDbState(latest, OrderEvent.RECOVER_FAIL, null, OrderExceptionEnum.ORDER_STATUS_ERROR);
            }
        } catch (Exception ignore) {
            log.warn("订单补偿失败事件流转异常，orderId={}, reason={}", orderId, reason);
        }
        orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .set(Order::getStatus, OrderState.REFUNDING.getCode())
                .set(Order::getUpdateTime, LocalDateTime.now())
                .eq(Order::getId, orderId)
                .in(Order::getStatus, Arrays.asList(OrderState.CLOSING.getCode(), OrderState.PAY_RECOVERING.getCode())));
    }

    private void markParentRefunding(Long parentOrderId, String tradeNo, String buyerId, LocalDateTime paymentTime) {
        if (parentOrderId == null) {
            return;
        }
        parentOrderMapper.update(null, new LambdaUpdateWrapper<ParentOrder>()
                .set(ParentOrder::getStatus, OrderState.REFUNDING.getCode())
                .set(StringUtils.hasText(tradeNo), ParentOrder::getTradeNo, tradeNo)
                .set(StringUtils.hasText(buyerId), ParentOrder::getBuyerId, buyerId)
                .set(paymentTime != null, ParentOrder::getPaymentTime, paymentTime)
                .set(ParentOrder::getUpdateTime, LocalDateTime.now())
                .eq(ParentOrder::getId, parentOrderId));
    }
}
