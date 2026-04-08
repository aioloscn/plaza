package com.aiolos.plaza.order.application.payment.notify;

import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.application.payment.notify.model.PaymentResultCommand;
import com.aiolos.plaza.order.domain.order.status.OrderStateJudge;
import com.aiolos.plaza.order.domain.order.status.OrderStatusMetadataResolver;
import com.aiolos.plaza.order.application.payment.notify.model.ParentPaymentAdvanceResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * 父单支付推进服务
 * 负责把支付成功回调下的父单推进到已支付态，并处理并发幂等结果
 */
@Slf4j
@Component
public class ParentPaymentAdvanceAppService {

    private final ParentOrderMapper parentOrderMapper;
    private final OrderStatusMetadataResolver orderStatusMetadataResolver;
    private final OrderStateJudge orderStateJudge;

    public ParentPaymentAdvanceAppService(ParentOrderMapper parentOrderMapper,
                                       OrderStatusMetadataResolver orderStatusMetadataResolver,
                                       OrderStateJudge orderStateJudge) {
        this.parentOrderMapper = parentOrderMapper;
        this.orderStatusMetadataResolver = orderStatusMetadataResolver;
        this.orderStateJudge = orderStateJudge;
    }

    public ParentPaymentAdvanceResult advanceAfterPay(PaymentResultCommand command, boolean hasClosingChild) {
        LocalDateTime now = LocalDateTime.now();
        // 只允许从待支付相关状态推进父单，避免重复回调覆盖后续状态
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
            // CAS 失败后重新看数据库最新态，已经支付则按幂等成功返回
            ParentOrder latestParentOrder = loadParentOrder(command.outTradeNo());
            if (latestParentOrder != null && orderStateJudge.isPaidOrAfter(latestParentOrder)) {
                log.info("并发支付回调已处理，幂等返回 success: {}", command.outTradeNo());
                return ParentPaymentAdvanceResult.stop("success");
            }
            log.error("支付回调 CAS 更新父订单失败，parentOrderSn={}", command.outTradeNo());
            return ParentPaymentAdvanceResult.stop("fail");
        }
        // 更新成功后重新回表，保证后续链路拿到的是已落库的新状态
        ParentOrder latestParentOrder = loadParentOrder(command.outTradeNo());
        if (latestParentOrder == null) {
            log.error("支付回调推进父单成功后重新加载失败: {}", command.outTradeNo());
            return ParentPaymentAdvanceResult.stop("fail");
        }
        return ParentPaymentAdvanceResult.proceed(latestParentOrder);
    }

    private ParentOrder loadParentOrder(String parentOrderSn) {
        // 父单号唯一，这里统一按单号回表取最新记录
        return parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, parentOrderSn)
                .last("LIMIT 1"));
    }
}
