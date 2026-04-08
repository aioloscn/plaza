package com.aiolos.plaza.order.domain.order.status;

import com.aiolos.plaza.enums.OrderAftersaleStatus;
import com.aiolos.plaza.enums.OrderFulfillmentStatus;
import com.aiolos.plaza.enums.OrderPaymentStatus;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.ParentOrder;
import org.springframework.stereotype.Component;

/**
 * 订单状态判定收口组件
 * 统一遵循三维状态优先，display status 兜底
 */
@Component
public class OrderStateJudge {

    public boolean isPaidOrAfter(ParentOrder parentOrder) {
        if (parentOrder == null) {
            return false;
        }
        return isPaidOrAfter(parentOrder.getPaymentStatus(), parentOrder.getStatus());
    }

    public boolean isPaidOrAfter(Order order) {
        if (order == null) {
            return false;
        }
        return isPaidOrAfter(order.getPaymentStatus(), order.getStatus());
    }

    public boolean isPaidOrAfter(Integer paymentStatus, Integer displayStatus) {
        if (OrderPaymentStatus.PAID.getCode().equals(paymentStatus)
                || OrderPaymentStatus.REFUNDING.getCode().equals(paymentStatus)
                || OrderPaymentStatus.REFUNDED.getCode().equals(paymentStatus)
                || OrderPaymentStatus.REFUND_FAILED.getCode().equals(paymentStatus)) {
            return true;
        }
        return OrderState.PAID.getCode().equals(displayStatus)
                || OrderState.DELIVERED.getCode().equals(displayStatus)
                || OrderState.COMPLETED.getCode().equals(displayStatus)
                || OrderState.REFUNDED.getCode().equals(displayStatus);
    }

    public boolean isRefundFlow(ParentOrder parentOrder) {
        if (parentOrder == null) {
            return false;
        }
        return isRefundFlow(parentOrder.getPaymentStatus(), parentOrder.getAftersaleStatus(), parentOrder.getStatus());
    }

    public boolean isRefundFlow(Order order) {
        if (order == null) {
            return false;
        }
        return isRefundFlow(order.getPaymentStatus(), order.getAftersaleStatus(), order.getStatus());
    }

    public boolean isRefundFlow(Integer paymentStatus, Integer aftersaleStatus, Integer displayStatus) {
        if (OrderPaymentStatus.COMPENSATING.getCode().equals(paymentStatus)
                || OrderPaymentStatus.REFUNDING.getCode().equals(paymentStatus)
                || OrderPaymentStatus.REFUNDED.getCode().equals(paymentStatus)
                || OrderPaymentStatus.REFUND_FAILED.getCode().equals(paymentStatus)) {
            return true;
        }
        if (OrderAftersaleStatus.REFUNDING.getCode().equals(aftersaleStatus)
                || OrderAftersaleStatus.REFUNDED.getCode().equals(aftersaleStatus)
                || OrderAftersaleStatus.REFUND_FAILED.getCode().equals(aftersaleStatus)) {
            return true;
        }
        return OrderState.PAY_RECOVERING.getCode().equals(displayStatus)
                || OrderState.REFUNDING.getCode().equals(displayStatus)
                || OrderState.REFUNDED.getCode().equals(displayStatus)
                || OrderState.REFUND_FAILED.getCode().equals(displayStatus);
    }

    public boolean isParentPayableForNotify(ParentOrder parentOrder) {
        if (parentOrder == null) {
            return false;
        }
        if (OrderPaymentStatus.UNPAID.getCode().equals(parentOrder.getPaymentStatus())
                || OrderPaymentStatus.PAYING.getCode().equals(parentOrder.getPaymentStatus())) {
            return true;
        }
        return isParentPayableForNotify(parentOrder.getStatus());
    }

    public boolean isParentPayableForNotify(Integer displayStatus) {
        return OrderState.CREATED.getCode().equals(displayStatus)
                || OrderState.PAYING.getCode().equals(displayStatus)
                || OrderState.CLOSING.getCode().equals(displayStatus);
    }

    public boolean isClosed(ParentOrder parentOrder) {
        if (parentOrder == null) {
            return false;
        }
        if (OrderFulfillmentStatus.CLOSED.getCode().equals(parentOrder.getFulfillmentStatus())) {
            return true;
        }
        return isClosed(parentOrder.getStatus());
    }

    public boolean isClosed(Order order) {
        if (order == null) {
            return false;
        }
        if (OrderFulfillmentStatus.CLOSED.getCode().equals(order.getFulfillmentStatus())) {
            return true;
        }
        return isClosed(order.getStatus());
    }

    public boolean isClosed(Integer displayStatus) {
        return OrderState.CLOSED.getCode().equals(displayStatus);
    }

    public boolean isPrePay(ParentOrder parentOrder) {
        if (parentOrder == null) {
            return false;
        }
        Integer paymentStatus = parentOrder.getPaymentStatus();
        if (OrderPaymentStatus.UNPAID.getCode().equals(paymentStatus)
                || OrderPaymentStatus.PAYING.getCode().equals(paymentStatus)
                || OrderPaymentStatus.COMPENSATING.getCode().equals(paymentStatus)) {
            return true;
        }
        return OrderState.CREATED.getCode().equals(parentOrder.getStatus())
                || OrderState.PAYING.getCode().equals(parentOrder.getStatus())
                || OrderState.CLOSING.getCode().equals(parentOrder.getStatus())
                || OrderState.PAY_RECOVERING.getCode().equals(parentOrder.getStatus());
    }

    public boolean isRefunding(Order order) {
        if (order == null) {
            return false;
        }
        if (OrderAftersaleStatus.REFUNDING.getCode().equals(order.getAftersaleStatus())
                || OrderPaymentStatus.REFUNDING.getCode().equals(order.getPaymentStatus())) {
            return true;
        }
        return OrderState.REFUNDING.getCode().equals(order.getStatus());
    }

    public boolean isRefunded(Order order) {
        if (order == null) {
            return true;
        }
        if (OrderAftersaleStatus.REFUNDED.getCode().equals(order.getAftersaleStatus())
                || OrderPaymentStatus.REFUNDED.getCode().equals(order.getPaymentStatus())) {
            return true;
        }
        return OrderState.REFUNDED.getCode().equals(order.getStatus());
    }

    public boolean isRefunded(ParentOrder parentOrder) {
        if (parentOrder == null) {
            return true;
        }
        if (OrderAftersaleStatus.REFUNDED.getCode().equals(parentOrder.getAftersaleStatus())
                || OrderPaymentStatus.REFUNDED.getCode().equals(parentOrder.getPaymentStatus())) {
            return true;
        }
        return OrderState.REFUNDED.getCode().equals(parentOrder.getStatus());
    }
}
