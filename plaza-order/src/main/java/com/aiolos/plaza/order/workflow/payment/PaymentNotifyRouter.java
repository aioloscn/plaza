package com.aiolos.plaza.order.workflow.payment;

import com.aiolos.plaza.model.po.ParentOrder;
import org.springframework.stereotype.Component;

@Component
public class PaymentNotifyRouter {

    public PaymentNotifyScenario route(ParentOrder parentOrder,
                                       PaymentNotifyCommand command,
                                       PaymentNotifyPrecheck precheck,
                                       PaymentNotifySupport support) {
        if (support.isRefundFlowStatus(parentOrder.getStatus())) {
            return PaymentNotifyScenario.ALREADY_IN_REFUND_FLOW;
        }
        if (support.isClosedStatus(parentOrder.getStatus())) {
            return PaymentNotifyScenario.CLOSED_PARENT;
        }
        if (support.isPaidOrAfter(parentOrder.getStatus())) {
            if (support.hasTradeNoConflict(parentOrder, command.tradeNo())) {
                return PaymentNotifyScenario.TRADE_NO_CONFLICT;
            }
            return PaymentNotifyScenario.ALREADY_PAID;
        }
        if (!support.isParentPayableForNotify(parentOrder.getStatus())) {
            return PaymentNotifyScenario.IGNORE_ILLEGAL_PARENT_STATUS;
        }
        if (precheck.hasClosedChild()) {
            return PaymentNotifyScenario.CLOSED_CHILD;
        }
        return PaymentNotifyScenario.NORMAL_PAY;
    }
}
