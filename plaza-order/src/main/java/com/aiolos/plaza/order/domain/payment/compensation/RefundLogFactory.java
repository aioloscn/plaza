package com.aiolos.plaza.order.domain.payment.compensation;

import com.aiolos.plaza.model.po.RefundLog;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 退款日志工厂
 */
@Component
public class RefundLogFactory {

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
}
