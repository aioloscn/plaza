package com.aiolos.plaza.order.domain.payment.compensation;

import com.aiolos.plaza.enums.PaymentCompensationReasonCode;
import com.aiolos.plaza.enums.RefundOrderStatus;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.model.po.RefundOrder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 退款单工厂
 */
@Component
public class RefundOrderFactory {

    private static final int DEFAULT_MAX_RETRY_COUNT = 8;

    public RefundOrder buildRefundOrder(ParentOrder parentOrder,
                                        String refundRequestNo,
                                        PaymentCompensationReasonCode reasonCode) {
        LocalDateTime now = LocalDateTime.now();
        RefundOrder refundOrder = new RefundOrder();
        refundOrder.setRefundRequestNo(refundRequestNo);
        refundOrder.setParentOrderSn(parentOrder.getParentOrderSn());
        refundOrder.setTradeNo(parentOrder.getTradeNo());
        refundOrder.setPayType(parentOrder.getPayType());
        refundOrder.setRefundAmount(parentOrder.getPayAmount());
        refundOrder.setStatus(RefundOrderStatus.INIT.getCode());
        refundOrder.setRetryCount(0);
        refundOrder.setMaxRetryCount(DEFAULT_MAX_RETRY_COUNT);
        refundOrder.setNextRetryTime(now);
        refundOrder.setReasonCode(reasonCode == null ? null : reasonCode.getCode());
        refundOrder.setCreateTime(now);
        refundOrder.setUpdateTime(now);
        return refundOrder;
    }

    public String buildRefundRequestNo(String parentOrderSn) {
        return "RF-" + parentOrderSn;
    }
}
