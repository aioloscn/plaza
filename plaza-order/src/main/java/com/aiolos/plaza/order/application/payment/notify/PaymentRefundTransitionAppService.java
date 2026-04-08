package com.aiolos.plaza.order.application.payment.notify;

import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.domain.order.status.OrderStatusMetadataResolver;
import com.aiolos.plaza.order.statemachine.config.OrderStateMachineService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付回调下的退款态推进服务
 * 负责把父子单推进到退款中，供后续退款补偿链路统一接管
 */
@Component
public class PaymentRefundTransitionAppService {

    private final OrderMapper orderMapper;
    private final ParentOrderMapper parentOrderMapper;
    private final OrderStatusMetadataResolver orderStatusMetadataResolver;
    private final OrderStateMachineService orderStateMachineService;

    public PaymentRefundTransitionAppService(OrderMapper orderMapper,
                                          ParentOrderMapper parentOrderMapper,
                                          OrderStatusMetadataResolver orderStatusMetadataResolver,
                                          OrderStateMachineService orderStateMachineService) {
        this.orderMapper = orderMapper;
        this.parentOrderMapper = parentOrderMapper;
        this.orderStatusMetadataResolver = orderStatusMetadataResolver;
        this.orderStateMachineService = orderStateMachineService;
    }

    public void markChildrenRefunding(String parentOrderSn, String reason) {
        // 父单号缺失时不进入退款态推进，避免误扫全表
        if (!StringUtils.hasText(parentOrderSn)) {
            return;
        }
        List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, parentOrderSn));
        for (Order child : childOrders) {
            // 每个子单再次回表，尽量基于最新状态做退款态判断
            Order latest = orderMapper.selectById(child.getId());
            if (latest == null) {
                continue;
            }
            // 已经进入退款终态或履约后态时不再重复改写
            if (OrderState.REFUNDING.getCode().equals(latest.getStatus())
                    || OrderState.REFUNDED.getCode().equals(latest.getStatus())
                    || OrderState.REFUND_FAILED.getCode().equals(latest.getStatus())
                    || OrderState.DELIVERED.getCode().equals(latest.getStatus())
                    || OrderState.COMPLETED.getCode().equals(latest.getStatus())) {
                continue;
            }
            boolean accepted = orderStateMachineService.sendOrderEventWithDbState(
                    latest,
                    OrderEvent.RECOVER_FAIL,
                    null,
                    OrderExceptionEnum.ORDER_STATUS_ERROR
            );
            if (!accepted) {
                // 某些中间态无法再走事件时，直接落库收口到退款中
                orderMapper.update(null, orderStatusMetadataResolver.applyToOrderUpdate(
                                new LambdaUpdateWrapper<Order>(),
                                OrderState.REFUNDING.getCode()
                        )
                        .set(Order::getUpdateTime, LocalDateTime.now())
                        .eq(Order::getId, latest.getId()));
            }
        }
    }

    public void markParentRefunding(Long parentOrderId, String tradeNo, String buyerId, LocalDateTime paymentTime) {
        // 父单只做字段补齐和退款中标记，后续退款补偿链路以此为入口继续推进
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
}
