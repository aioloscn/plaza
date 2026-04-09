package com.aiolos.plaza.order.application.payment.refund;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.OrderType;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.application.stock.reservation.StockReservationService;
import com.aiolos.plaza.order.domain.order.status.OrderStateJudge;
import com.aiolos.plaza.order.domain.order.status.OrderStatusMetadataResolver;
import com.aiolos.plaza.order.application.order.status.ParentOrderRefreshAppService;
import com.aiolos.plaza.order.application.payment.gateway.AlipayGatewaySupport;
import com.aiolos.plaza.order.statemachine.config.OrderStateMachineService;
import com.alibaba.fastjson.JSONObject;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付补偿失败退款收敛服务
 * 负责整笔退款执行以及父子单退款结果收敛
 */
@Slf4j
@Service
public class PaymentRefundSettlementAppService {

    private final ParentOrderMapper parentOrderMapper;
    private final OrderMapper orderMapper;
    private final OrderStateJudge orderStateJudge;
    private final OrderStateMachineService orderStateMachineService;
    private final OrderStatusMetadataResolver orderStatusMetadataResolver;
    private final ParentOrderRefreshAppService parentOrderRefreshAppService;
    private final AlipayGatewaySupport alipayGatewaySupport;
    private final StockReservationService stockReservationService;
    private final StringRedisTemplate shopRedisTemplate;
    private final OrderItemMapper orderItemMapper;

    /**
     * 构造退款收敛服务，注入订单、支付网关、状态机与库存回滚依赖。
     */
    public PaymentRefundSettlementAppService(ParentOrderMapper parentOrderMapper,
                                          OrderMapper orderMapper,
                                          OrderStateJudge orderStateJudge,
                                          OrderStateMachineService orderStateMachineService,
                                          OrderStatusMetadataResolver orderStatusMetadataResolver,
                                          ParentOrderRefreshAppService parentOrderRefreshAppService,
                                          AlipayGatewaySupport alipayGatewaySupport,
                                          StockReservationService stockReservationService,
                                          @Qualifier("shopRedisTemplate") StringRedisTemplate shopRedisTemplate,
                                          OrderItemMapper orderItemMapper) {
        this.parentOrderMapper = parentOrderMapper;
        this.orderMapper = orderMapper;
        this.orderStateJudge = orderStateJudge;
        this.orderStateMachineService = orderStateMachineService;
        this.orderStatusMetadataResolver = orderStatusMetadataResolver;
        this.parentOrderRefreshAppService = parentOrderRefreshAppService;
        this.alipayGatewaySupport = alipayGatewaySupport;
        this.stockReservationService = stockReservationService;
        this.shopRedisTemplate = shopRedisTemplate;
        this.orderItemMapper = orderItemMapper;
    }

    /**
     * 执行整笔退款收敛：
     * 1. 校验父单状态；
     * 2. 调用支付网关退款；
     * 3. 回滚预占库存并推进父子单退款状态。
     */
    public void settle(String parentOrderSn) {
        if (!StringUtils.hasText(parentOrderSn)) {
            return;
        }
        ParentOrder parentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, parentOrderSn));
        if (parentOrder == null || orderStateJudge.isRefunded(parentOrder)) {
            return;
        }
        if (!orderStateJudge.isRefundFlow(parentOrder)) {
            return;
        }
        List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, parentOrderSn));
        if (childOrders == null || childOrders.isEmpty()) {
            throw new RuntimeException("退款失败：父订单下不存在子订单，parentOrderSn=" + parentOrderSn);
        }

        AlipayTradeRefundResponse response = executeGatewayRefund(parentOrderSn, parentOrder);
        if (response == null) {
            throw new RuntimeException("支付宝退款响应为空");
        }
        if (!response.isSuccess() || !"Y".equalsIgnoreCase(response.getFundChange())) {
            log.error("支付宝退款失败，parentOrderSn={}, subCode={}, subMsg={}, body={}",
                    parentOrderSn, response.getSubCode(), response.getSubMsg(), response.getBody());
            markRefundFailed(childOrders);
            parentOrderRefreshAppService.refresh(parentOrderSn);
            return;
        }

        for (Order child : childOrders) {
            if (StringUtils.hasText(child.getReservationNo())) {
                stockReservationService.rollbackConfirmed(child.getReservationNo());
            }
            clearSeckillBoughtUserIfNeeded(child);
            Order latest = orderMapper.selectById(child.getId());
            if (orderStateJudge.isRefunding(latest)) {
                boolean accepted = orderStateMachineService.sendOrderEventWithDbState(
                        latest, OrderEvent.REFUND_SUCCESS, null, OrderExceptionEnum.ORDER_REFUND_FAIL);
                if (!accepted) {
                    ExceptionUtil.throwException(OrderExceptionEnum.ORDER_REFUND_FAIL);
                }
            }
        }
        parentOrderRefreshAppService.refresh(parentOrderSn);
        log.info("退款成功，父订单已收敛，parentOrderSn={}", parentOrderSn);
    }

    /**
     * 调用支付宝退款接口，返回原始退款响应。
     */
    private AlipayTradeRefundResponse executeGatewayRefund(String parentOrderSn, ParentOrder parentOrder) {
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

        try {
            return alipayGatewaySupport.buildClient().execute(request);
        } catch (Exception ex) {
            log.error("调用支付宝退款接口异常，parentOrderSn={}", parentOrderSn, ex);
            throw new RuntimeException("调用支付宝退款接口异常", ex);
        }
    }

    /**
     * 当网关退款失败时，把仍处于退款中的子单推进到退款失败状态。
     */
    private void markRefundFailed(List<Order> childOrders) {
        if (childOrders == null || childOrders.isEmpty()) {
            return;
        }
        for (Order child : childOrders) {
            Order latest = orderMapper.selectById(child.getId());
            if (orderStateJudge.isRefunding(latest)) {
                boolean accepted = orderStateMachineService.sendOrderEventWithDbState(
                        latest, OrderEvent.REFUND_FAIL, null, OrderExceptionEnum.ORDER_REFUND_FAIL);
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

    /**
     * 退款成功后若为秒杀订单，移除已购标记，允许用户再次参与活动。
     */
    private void clearSeckillBoughtUserIfNeeded(Order order) {
        if (order == null) {
            return;
        }
        if (!OrderType.SECKILL.getCode().equals(order.getOrderType())) {
            return;
        }
        if (order.getActivityId() == null || order.getUserId() == null) {
            return;
        }
        String boughtKey = RedisKeyEnum.SECKILL_BOUGHT_USERS.getKey(order.getActivityId());
        Long removed = shopRedisTemplate.opsForSet().remove(boughtKey, String.valueOf(order.getUserId()));
        if (removed == null || removed <= 0) {
            return;
        }
        List<OrderItem> orderItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()));
        int totalQuantity = 0;
        for (OrderItem item : orderItems) {
            if (item == null || item.getProductQuantity() == null || item.getProductQuantity() <= 0) {
                continue;
            }
            totalQuantity += item.getProductQuantity();
        }
        if (totalQuantity > 0) {
            String stockKey = RedisKeyEnum.SECKILL_STOCK.getKey(order.getActivityId());
            shopRedisTemplate.opsForValue().increment(stockKey, totalQuantity);
        }
    }
}
