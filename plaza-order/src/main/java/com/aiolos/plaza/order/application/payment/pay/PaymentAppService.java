package com.aiolos.plaza.order.application.payment.pay;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.application.payment.compensation.PaymentCompensationTaskScheduler;
import com.aiolos.plaza.order.application.payment.refund.PaymentRefundSettlementAppService;
import com.aiolos.plaza.order.application.order.status.ParentOrderRefreshAppService;
import com.aiolos.plaza.order.application.payment.PaymentService;
import com.aiolos.plaza.order.application.payment.refund.UserRefundAppService;
import com.aiolos.plaza.order.config.AlipayConfig;
import com.aiolos.plaza.order.application.stock.reservation.StockReservationService;
import com.aiolos.plaza.order.domain.order.status.OrderStateJudge;
import com.aiolos.plaza.order.application.payment.gateway.AlipayGatewaySupport;
import com.aiolos.plaza.order.application.payment.notify.PaymentNotifyRouter;
import com.aiolos.plaza.order.application.payment.notify.PaymentNotifyScenario;
import com.aiolos.plaza.order.application.payment.notify.model.PaymentResultPrecheck;
import com.aiolos.plaza.order.application.payment.notify.model.PaymentResultCommand;
import com.aiolos.plaza.order.application.payment.notify.model.PaymentResultContext;
import com.aiolos.plaza.order.domain.order.status.OrderStatusMetadataResolver;
import com.aiolos.plaza.order.application.payment.notify.PaymentNotifyOrchestrator;
import com.alipay.api.AlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeWapPayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
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
public class PaymentAppService implements PaymentService {

    @Autowired
    private ParentOrderMapper parentOrderMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StockReservationService stockReservationService;

    @Autowired
    private PaymentNotifyRouter paymentNotifyRouter;

    @Autowired
    private PaymentNotifyOrchestrator paymentNotifyOrchestrator;

    @Autowired
    private AlipayGatewaySupport alipayGatewaySupport;

    @Autowired
    private AlipayConfig alipayConfig;

    @Autowired
    private OrderStatusMetadataResolver orderStatusMetadataResolver;

    @Autowired
    private ParentOrderRefreshAppService parentOrderRefreshAppService;

    @Autowired
    private PaymentCompensationTaskScheduler paymentCompensationTaskScheduler;

    @Autowired
    private UserRefundAppService userRefundAppService;

    @Autowired
    private PaymentRefundSettlementAppService paymentRefundSettlementAppService;

    @Autowired
    private OrderStateJudge orderStateJudge;

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
                    stockReservationService.extendExpireTime(child.getReservationNo(), now.plusMinutes(2));
                }
            }
        }
        parentOrderRefreshAppService.refresh(orderSn);

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
            PaymentResultCommand command = buildNotifyCommand(params);
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
            PaymentResultPrecheck precheck = paymentNotifyOrchestrator.inspectChildOrders(command.outTradeNo());
            PaymentNotifyScenario scenario = paymentNotifyRouter.route(parentOrder, command, precheck, paymentNotifyOrchestrator);
            return scenario.handle(new PaymentResultContext(parentOrder, command, precheck), paymentNotifyOrchestrator);
        } catch (Exception e) {
            log.error("支付宝回调处理异常", e);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return "fail";
        }
    }

    @Override
    public String refundNotify(Map<String, String> params) {
        return paymentCompensationTaskScheduler.handleRefundNotify(params);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String refund(Long userId, String parentOrderSn) {
        return userRefundAppService.apply(userId, parentOrderSn);
    }

    private boolean verifyNotifySignature(Map<String, String> params) {
        boolean verified = alipayGatewaySupport.verifySignature(params);
        if (!verified) {
            log.error("支付宝回调验签失败");
        }
        return verified;
    }

    private PaymentResultCommand buildNotifyCommand(Map<String, String> params) {
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
            return new PaymentResultCommand(outTradeNo, tradeStatus, new BigDecimal(totalAmount), tradeNo, buyerId);
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

    private boolean isNotifyAmountMatched(ParentOrder parentOrder, PaymentResultCommand command) {
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
        paymentRefundSettlementAppService.settle(parentOrderSn);
    }

    private void assertParentOrderPayable(ParentOrder parentOrder) {
        if (orderStateJudge.isPaidOrAfter(parentOrder)
                || orderStateJudge.isClosed(parentOrder)) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }
        if (orderStateJudge.isRefundFlow(parentOrder)
                || !orderStateJudge.isParentPayableForNotify(parentOrder)) {
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
