package com.aiolos.plaza.order.application.payment.notify;

import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.application.payment.notify.model.PaymentResultPrecheck;
import com.aiolos.plaza.order.application.payment.notify.model.PaymentResultCommand;
import com.aiolos.plaza.order.domain.order.status.OrderStateJudge;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PaymentNotifyRouter {

    @Autowired
    private OrderStateJudge orderStateJudge;

    public PaymentNotifyScenario route(ParentOrder parentOrder,
                                       PaymentResultCommand command,
                                       PaymentResultPrecheck precheck,
                                       PaymentNotifyOrchestrator support) {
        // 已经进入退款流时不再按正常支付成功处理
        if (orderStateJudge.isRefundFlow(parentOrder)) {
            return PaymentNotifyScenario.ALREADY_IN_REFUND_FLOW;
        }
        // 父单已经关闭时只能走关单后的兜底分支
        if (orderStateJudge.isClosed(parentOrder)) {
            return PaymentNotifyScenario.CLOSED_PARENT;
        }
        // 已支付父单主要区分是否是 tradeNo 冲突
        if (orderStateJudge.isPaidOrAfter(parentOrder)) {
            if (support.hasTradeNoConflict(parentOrder, command.tradeNo())) {
                return PaymentNotifyScenario.TRADE_NO_CONFLICT;
            }
            return PaymentNotifyScenario.ALREADY_PAID;
        }
        // 非法父单状态直接忽略，避免异常回调把状态推乱
        if (!orderStateJudge.isParentPayableForNotify(parentOrder)) {
            return PaymentNotifyScenario.IGNORE_ILLEGAL_PARENT_STATUS;
        }
        // 子单里已经有关闭项时，父单要走支付后退款恢复路径
        if (precheck.hasClosedChild()) {
            return PaymentNotifyScenario.CLOSED_CHILD;
        }
        return PaymentNotifyScenario.NORMAL_PAY;
    }
}
