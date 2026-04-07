package com.aiolos.plaza.order.domain.payment;

import com.aiolos.plaza.enums.PaymentCompensationReasonCode;
import com.aiolos.plaza.enums.PaymentCompensationTaskStatus;
import com.aiolos.plaza.enums.PaymentCompensationType;
import com.aiolos.plaza.enums.RefundOrderStatus;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.model.po.PaymentCompensationTask;
import com.aiolos.plaza.model.po.RefundLog;
import com.aiolos.plaza.model.po.RefundOrder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 统一构建支付补偿任务、退款单和退款日志
 */
@Component
public class PaymentCompensationTaskFactory {

    private static final int DEFAULT_MAX_RETRY_COUNT = 8;

    public PaymentCompensationTask buildTask(PaymentCompensationType type,
                                             String businessKey,
                                             String parentOrderSn,
                                             String orderSn,
                                             String tradeNo,
                                             String refundRequestNo,
                                             PaymentCompensationReasonCode reasonCode,
                                             LocalDateTime nextRetryTime) {
        LocalDateTime now = LocalDateTime.now();
        PaymentCompensationTask task = new PaymentCompensationTask();
        task.setTaskNo("PT-" + UUID.randomUUID().toString().replace("-", ""));
        task.setBusinessKey(businessKey);
        task.setCompensationType(type.getCode());
        task.setParentOrderSn(parentOrderSn);
        task.setOrderSn(orderSn);
        task.setTradeNo(tradeNo);
        task.setRefundRequestNo(refundRequestNo);
        task.setStatus(PaymentCompensationTaskStatus.INIT.getCode());
        task.setRetryCount(0);
        task.setMaxRetryCount(DEFAULT_MAX_RETRY_COUNT);
        task.setNextRetryTime(nextRetryTime == null ? now : nextRetryTime);
        task.setReasonCode(reasonCode == null ? null : reasonCode.getCode());
        task.setCreateTime(now);
        task.setUpdateTime(now);
        return task;
    }

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

    public RefundLog buildRefundLog(String refundRequestNo,
                                    String actionType,
                                    String actionStatus,
                                    String requestPayload,
                                    String responsePayload,
                                    String message) {
        RefundLog refundLog = new RefundLog();
        refundLog.setRefundRequestNo(refundRequestNo);
        refundLog.setActionType(actionType);
        refundLog.setActionStatus(actionStatus);
        refundLog.setRequestPayload(requestPayload);
        refundLog.setResponsePayload(responsePayload);
        refundLog.setMessage(message);
        refundLog.setCreateTime(LocalDateTime.now());
        return refundLog;
    }

    public String buildRefundRequestNo(String parentOrderSn) {
        return "RF-" + parentOrderSn;
    }
}
