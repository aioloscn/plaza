package com.aiolos.plaza.order.domain.payment.compensation;

import com.aiolos.plaza.enums.PaymentCompensationReasonCode;
import com.aiolos.plaza.enums.PaymentCompensationTaskStatus;
import com.aiolos.plaza.enums.PaymentCompensationType;
import com.aiolos.plaza.model.po.PaymentCompensationTask;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 支付补偿任务工厂
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
}
