package com.aiolos.plaza.order.facade;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.PaymentCompensationReasonCode;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.application.payment.PaymentCompensationTaskService;
import com.aiolos.plaza.order.application.payment.RefundCompensationCommandService;
import com.aiolos.plaza.order.config.AlipayConfig;
import com.aiolos.plaza.order.coreflow.inventory.service.OrderInventoryService;
import com.aiolos.plaza.order.api.PaymentService;
import com.aiolos.plaza.order.domain.status.ParentStatusDomainService;
import com.aiolos.plaza.order.workflow.payment.AlipayGatewaySupport;
import com.aiolos.plaza.order.workflow.payment.PaymentNotifyCommand;
import com.aiolos.plaza.order.workflow.payment.PaymentNotifyContext;
import com.aiolos.plaza.order.workflow.payment.PaymentNotifyPrecheck;
import com.aiolos.plaza.order.workflow.payment.PaymentNotifyRouter;
import com.aiolos.plaza.order.workflow.payment.PaymentNotifyScenario;
import com.aiolos.plaza.order.domain.status.OrderStatusMetadataResolver;
import com.aiolos.plaza.order.workflow.payment.PaymentNotifySupport;
import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeWapPayModel;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PaymentFacade implements PaymentService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ParentOrderMapper parentOrderMapper;

    @Autowired
    private OrderInventoryService orderInventoryService;

    @Autowired
    private PaymentNotifyRouter paymentNotifyRouter;

    @Autowired
    private PaymentNotifySupport paymentNotifySupport;

    @Autowired
    private AlipayGatewaySupport alipayGatewaySupport;

    @Autowired
    private AlipayConfig alipayConfig;

    @Autowired
    private OrderStatusMetadataResolver orderStatusMetadataResolver;

    @Autowired
    private ParentStatusDomainService parentStatusDomainService;

    @Autowired
    private PaymentCompensationTaskService paymentCompensationTaskService;

    @Autowired
    private RefundCompensationCommandService refundCompensationCommandService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String pay(Long userId, String orderSn, Integer payType, boolean isMobile) {
        // 支付入口只负责支付域逻辑，订单创建与购物车流程仍由 PlazaOrderService 承担
        ParentOrder parentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, orderSn)
                .eq(ParentOrder::getUserId, userId));
        if (parentOrder == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        assertParentOrderPayable(parentOrder);

        // 进入支付中，并延长预占时间，降低“支付过程中刚好过期关单”的概率
        LocalDateTime now = LocalDateTime.now();
        parentOrderMapper.update(null, orderStatusMetadataResolver.applyToParentUpdate(
                        new LambdaUpdateWrapper<ParentOrder>(),
                        OrderState.PAYING.getCode()
                )
                .set(ParentOrder::getUpdateTime, now)
                .eq(ParentOrder::getId, parentOrder.getId())
                .in(ParentOrder::getStatus, Arrays.asList(OrderState.CREATED.getCode(), OrderState.CLOSING.getCode())));
        orderMapper.update(null, orderStatusMetadataResolver.applyToOrderUpdate(
                        new LambdaUpdateWrapper<Order>(),
                        OrderState.PAYING.getCode()
                )
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
        parentStatusDomainService.recomputeParentOrderStatus(orderSn);

        try {
            return createPayForm(parentOrder, orderSn, isMobile);
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
            if (!verifyNotifySignature(params)) {
                return "fail";
            }
            PaymentNotifyCommand command = buildNotifyCommand(params);
            if (command == null) {
                return "fail";
            }
            if (!command.isTradeSuccess()) {
                return "success";
            }
            ParentOrder parentOrder = loadParentOrderForNotify(command.outTradeNo());
            if (parentOrder == null) {
                return "fail";
            }
            if (!isNotifyAmountMatched(parentOrder, command)) {
                return "fail";
            }
            PaymentNotifyPrecheck precheck = paymentNotifySupport.inspectChildOrders(command.outTradeNo());
            PaymentNotifyScenario scenario = paymentNotifyRouter.route(parentOrder, command, precheck, paymentNotifySupport);
            return scenario.handle(new PaymentNotifyContext(parentOrder, command, precheck), paymentNotifySupport);
        } catch (Exception e) {
            log.error("支付宝回调处理异常", e);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return "fail";
        }
    }

    @Override
    public String refundNotify(Map<String, String> params) {
        return paymentCompensationTaskService.handleRefundNotify(params);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String refund(Long userId, String parentOrderSn) {
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
        if (!OrderState.PAID.getCode().equals(parentOrder.getStatus())) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }

        List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, parentOrderSn));
        if (childOrders == null || childOrders.isEmpty()) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        for (Order child : childOrders) {
            if (!OrderState.PAID.getCode().equals(child.getStatus())) {
                ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
            }
        }

        for (Order child : childOrders) {
            boolean accepted = paymentNotifySupport.sendOrderEventWithDbState(
                    child, OrderEvent.APPLY_REFUND, null, OrderExceptionEnum.ORDER_REFUND_FAIL);
            if (!accepted) {
                ExceptionUtil.throwException(OrderExceptionEnum.ORDER_REFUND_FAIL);
            }
        }

        paymentNotifySupport.markParentRefunding(parentOrder.getId(),
                parentOrder.getTradeNo(), parentOrder.getBuyerId(), parentOrder.getPaymentTime());
        parentStatusDomainService.recomputeParentOrderStatus(parentOrderSn);

        String refundRequestNo = refundCompensationCommandService.submitRefundCompensation(
                parentOrderSn,
                parentOrder.getTradeNo(),
                PaymentCompensationReasonCode.REFUND_REQUEST_CREATED
        );
        paymentCompensationTaskService.executeRefundTaskIfReady(refundRequestNo);
        return refundRequestNo;
    }

    private boolean verifyNotifySignature(Map<String, String> params) {
        boolean verified = alipayGatewaySupport.verifySignature(params);
        if (!verified) {
            log.error("支付宝回调验签失败");
        }
        return verified;
    }

    private PaymentNotifyCommand buildNotifyCommand(Map<String, String> params) {
        String outTradeNo = params.get("out_trade_no");
        String tradeStatus = params.get("trade_status");
        String totalAmount = params.get("total_amount");
        String appId = params.get("app_id");
        String tradeNo = params.get("trade_no");
        String buyerId = params.get("buyer_id");
        if (!StringUtils.hasText(outTradeNo) || !StringUtils.hasText(totalAmount) || !StringUtils.hasText(appId)) {
            log.error("支付宝回调缺少关键参数: out_trade_no={}, total_amount={}, app_id={}", outTradeNo, totalAmount, appId);
            return null;
        }
        if (!alipayConfig.getAppId().equals(appId)) {
            log.error("支付宝回调 appId 不匹配");
            return null;
        }
        try {
            return new PaymentNotifyCommand(outTradeNo, tradeStatus, new BigDecimal(totalAmount), tradeNo, buyerId);
        } catch (NumberFormatException ex) {
            log.error("支付宝回调金额格式非法: {}", totalAmount, ex);
            return null;
        }
    }

    private ParentOrder loadParentOrderForNotify(String parentOrderSn) {
        ParentOrder parentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, parentOrderSn));
        if (parentOrder == null) {
            log.error("支付宝回调订单不存在: {}", parentOrderSn);
        }
        return parentOrder;
    }

    private boolean isNotifyAmountMatched(ParentOrder parentOrder, PaymentNotifyCommand command) {
        if (parentOrder.getPayAmount().compareTo(command.totalAmount()) != 0) {
            log.error("支付宝回调金额不匹配: 订单金额={}, 回调金额={}", parentOrder.getPayAmount(), command.totalAmount());
            return false;
        }
        return true;
    }


    /**
     * 处理支付补偿失败后的整笔退款
     * 成功则推进子单、父单到已退款；失败则推进到退款失败；异常交给 MQ 重试
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleRefund(String parentOrderSn) {
        if (!StringUtils.hasText(parentOrderSn)) {
            return;
        }
        ParentOrder parentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, parentOrderSn));
        if (parentOrder == null || OrderState.REFUNDED.getCode().equals(parentOrder.getStatus())) {
            return;
        }
        if (!OrderState.REFUNDING.getCode().equals(parentOrder.getStatus())) {
            return;
        }
        List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, parentOrderSn));
        if (childOrders == null || childOrders.isEmpty()) {
            throw new RuntimeException("退款失败：父订单下不存在子订单，parentOrderSn=" + parentOrderSn);
        }

        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        JSONObject bizContent = new JSONObject();
        bizContent.put("out_trade_no", parentOrderSn);
        if (StringUtils.hasText(parentOrder.getTradeNo())) {
            bizContent.put("trade_no", parentOrder.getTradeNo());
        }
        bizContent.put("refund_amount", parentOrder.getPayAmount().toPlainString());
        bizContent.put("refund_reason", "支付补偿失败退款");
        bizContent.put("out_request_no", "RF-" + parentOrderSn);
        request.setBizContent(bizContent.toJSONString());

        AlipayTradeRefundResponse response;
        try {
            response = alipayGatewaySupport.buildClient().execute(request);
        } catch (Exception ex) {
            log.error("调用支付宝退款接口异常，parentOrderSn={}", parentOrderSn, ex);
            throw new RuntimeException("调用支付宝退款接口异常", ex);
        }

        if (response == null) {
            throw new RuntimeException("支付宝退款响应为空");
        }
        if (!response.isSuccess() || !"Y".equalsIgnoreCase(response.getFundChange())) {
            log.error("支付宝退款失败，parentOrderSn={}, subCode={}, subMsg={}, body={}",
                    parentOrderSn, response.getSubCode(), response.getSubMsg(), response.getBody());
            markRefundFailed(childOrders);
            parentStatusDomainService.recomputeParentOrderStatus(parentOrderSn);
            return;
        }

        for (Order child : childOrders) {
            if (StringUtils.hasText(child.getReservationNo())) {
                orderInventoryService.rollbackConfirmed(child.getReservationNo());
            }
            Order latest = orderMapper.selectById(child.getId());
            if (latest != null && OrderState.REFUNDING.getCode().equals(latest.getStatus())) {
                boolean accepted = paymentNotifySupport.sendOrderEventWithDbState(latest, OrderEvent.REFUND_SUCCESS, null, OrderExceptionEnum.ORDER_REFUND_FAIL);
                if (!accepted) {
                    ExceptionUtil.throwException(OrderExceptionEnum.ORDER_REFUND_FAIL);
                }
            }
        }
        parentStatusDomainService.recomputeParentOrderStatus(parentOrderSn);
        log.info("退款成功，父订单已收敛，parentOrderSn={}", parentOrderSn);
    }

    private void markRefundFailed(List<Order> childOrders) {
        if (childOrders == null || childOrders.isEmpty()) {
            return;
        }
        for (Order child : childOrders) {
            Order latest = orderMapper.selectById(child.getId());
            if (latest != null && OrderState.REFUNDING.getCode().equals(latest.getStatus())) {
                boolean accepted = paymentNotifySupport.sendOrderEventWithDbState(latest, OrderEvent.REFUND_FAIL, null, OrderExceptionEnum.ORDER_REFUND_FAIL);
                if (!accepted) {
                    orderMapper.update(null, orderStatusMetadataResolver.applyToOrderUpdate(
                                    new LambdaUpdateWrapper<Order>(),
                                    OrderState.REFUND_FAILED.getCode()
                            )
                            .set(Order::getUpdateTime, LocalDateTime.now())
                            .eq(Order::getId, latest.getId())
                            .eq(Order::getStatus, OrderState.REFUNDING.getCode()));
                }
            }
        }
    }

    private void assertParentOrderPayable(ParentOrder parentOrder) {
        if (paymentNotifySupport.isPaidOrAfter(parentOrder.getStatus())
                || paymentNotifySupport.isClosedStatus(parentOrder.getStatus())) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }
        if (paymentNotifySupport.isRefundFlowStatus(parentOrder.getStatus())
                || !paymentNotifySupport.isParentPayableForNotify(parentOrder.getStatus())) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }
    }

    private String createPayForm(ParentOrder parentOrder, String orderSn, boolean isMobile) throws Exception {
        AlipayClient alipayClient = alipayGatewaySupport.buildClient();
        if (isMobile) {
            return buildWapPayForm(alipayClient, parentOrder, orderSn);
        }
        return buildPagePayForm(alipayClient, parentOrder, orderSn);
    }

    private String buildWapPayForm(AlipayClient alipayClient, ParentOrder parentOrder, String orderSn) throws Exception {
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
    }

    private String buildPagePayForm(AlipayClient alipayClient, ParentOrder parentOrder, String orderSn) throws Exception {
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

}
