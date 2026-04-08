package com.aiolos.plaza.order.domain.order.status;

import com.aiolos.plaza.enums.OrderAftersaleStatus;
import com.aiolos.plaza.enums.OrderFulfillmentStatus;
import com.aiolos.plaza.enums.OrderPaymentStatus;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.ParentOrder;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 把历史 display status 解析成支付、履约、售后三个维度
 */
@Component
public class OrderStatusMetadataResolver {

    public StatusMetadata resolve(Integer displayStatus) {
        // 展示态是历史兼容入口，内部统一先转为枚举再映射三维状态
        OrderState state = toOrderState(displayStatus);
        return switch (state) {
            case RESERVING -> buildMetadata(state, OrderPaymentStatus.UNPAID, OrderFulfillmentStatus.RESERVING, OrderAftersaleStatus.NONE);
            case CREATED, CLOSING -> buildMetadata(state, OrderPaymentStatus.UNPAID, OrderFulfillmentStatus.UNFULFILLED, OrderAftersaleStatus.NONE);
            case PAYING -> buildMetadata(state, OrderPaymentStatus.PAYING, OrderFulfillmentStatus.UNFULFILLED, OrderAftersaleStatus.NONE);
            case PAY_RECOVERING -> buildMetadata(state, OrderPaymentStatus.COMPENSATING, OrderFulfillmentStatus.UNFULFILLED, OrderAftersaleStatus.NONE);
            case PAID -> buildMetadata(state, OrderPaymentStatus.PAID, OrderFulfillmentStatus.TO_DELIVER, OrderAftersaleStatus.NONE);
            case DELIVERED -> buildMetadata(state, OrderPaymentStatus.PAID, OrderFulfillmentStatus.DELIVERED, OrderAftersaleStatus.NONE);
            case COMPLETED -> buildMetadata(state, OrderPaymentStatus.PAID, OrderFulfillmentStatus.COMPLETED, OrderAftersaleStatus.NONE);
            case CLOSED, INVALID -> {
                // 关闭/无效都归到“未支付 + 已关闭 + 无售后”的兜底维度
                yield buildMetadata(state, OrderPaymentStatus.UNPAID, OrderFulfillmentStatus.CLOSED, OrderAftersaleStatus.NONE);
            }
            case REFUNDING -> buildMetadata(state, OrderPaymentStatus.REFUNDING, OrderFulfillmentStatus.CLOSED, OrderAftersaleStatus.REFUNDING);
            case REFUNDED -> buildMetadata(state, OrderPaymentStatus.REFUNDED, OrderFulfillmentStatus.CLOSED, OrderAftersaleStatus.REFUNDED);
            case REFUND_FAILED -> buildMetadata(state, OrderPaymentStatus.REFUND_FAILED, OrderFulfillmentStatus.CLOSED, OrderAftersaleStatus.REFUND_FAILED);
        };
    }

    private StatusMetadata buildMetadata(OrderState state,
                                         OrderPaymentStatus paymentStatus,
                                         OrderFulfillmentStatus fulfillmentStatus,
                                         OrderAftersaleStatus aftersaleStatus) {
        // 统一构造结果，避免各分支手工拼装字段
        return new StatusMetadata(
                state.getCode(),
                state.getDesc(),
                paymentStatus.getCode(),
                fulfillmentStatus.getCode(),
                aftersaleStatus.getCode()
        );
    }

    public void fill(Order order, Integer displayStatus) {
        // 把展示态转换后的三维状态写回子单实体
        StatusMetadata metadata = resolve(displayStatus);
        order.setStatus(metadata.displayStatus());
        order.setPaymentStatus(metadata.paymentStatus());
        order.setFulfillmentStatus(metadata.fulfillmentStatus());
        order.setAftersaleStatus(metadata.aftersaleStatus());
    }

    public void fill(ParentOrder order, Integer displayStatus) {
        // 父单与子单共享同一套展示态 -> 三维状态映射规则
        StatusMetadata metadata = resolve(displayStatus);
        order.setStatus(metadata.displayStatus());
        order.setPaymentStatus(metadata.paymentStatus());
        order.setFulfillmentStatus(metadata.fulfillmentStatus());
        order.setAftersaleStatus(metadata.aftersaleStatus());
    }

    public void fill(ParentOrder order, ParentOrderStatusCalculator.ParentOrderStatusSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        // 聚合快照已是最终值，直接透传回父单
        order.setStatus(snapshot.displayStatus());
        order.setPaymentStatus(snapshot.paymentStatus());
        order.setFulfillmentStatus(snapshot.fulfillmentStatus());
        order.setAftersaleStatus(snapshot.aftersaleStatus());
    }

    public LambdaUpdateWrapper<Order> applyToOrderUpdate(LambdaUpdateWrapper<Order> updateWrapper, Integer displayStatus) {
        // 用于 DB 更新语句，确保展示态与三维状态在一次 SQL 中同时落库
        StatusMetadata metadata = resolve(displayStatus);
        return updateWrapper
                .set(Order::getStatus, metadata.displayStatus())
                .set(Order::getPaymentStatus, metadata.paymentStatus())
                .set(Order::getFulfillmentStatus, metadata.fulfillmentStatus())
                .set(Order::getAftersaleStatus, metadata.aftersaleStatus());
    }

    public LambdaUpdateWrapper<ParentOrder> applyToParentUpdate(LambdaUpdateWrapper<ParentOrder> updateWrapper, Integer displayStatus) {
        // 父单按展示态更新时，同步写入 payment/fulfillment/aftersale 三个维度
        StatusMetadata metadata = resolve(displayStatus);
        return updateWrapper
                .set(ParentOrder::getStatus, metadata.displayStatus())
                .set(ParentOrder::getPaymentStatus, metadata.paymentStatus())
                .set(ParentOrder::getFulfillmentStatus, metadata.fulfillmentStatus())
                .set(ParentOrder::getAftersaleStatus, metadata.aftersaleStatus());
    }

    public LambdaUpdateWrapper<ParentOrder> applyToParentUpdate(LambdaUpdateWrapper<ParentOrder> updateWrapper,
                                                                ParentOrderStatusCalculator.ParentOrderStatusSnapshot snapshot) {
        // 聚合结果更新入口，避免外层重复 set 四个状态字段
        return updateWrapper
                .set(ParentOrder::getStatus, snapshot.displayStatus())
                .set(ParentOrder::getPaymentStatus, snapshot.paymentStatus())
                .set(ParentOrder::getFulfillmentStatus, snapshot.fulfillmentStatus())
                .set(ParentOrder::getAftersaleStatus, snapshot.aftersaleStatus());
    }

    public String getDisplayStatusDesc(Integer displayStatus) {
        return resolve(displayStatus).displayStatusDesc();
    }

    public String getPaymentStatusDesc(Integer paymentStatus) {
        return findPaymentStatus(paymentStatus).getDesc();
    }

    public String getFulfillmentStatusDesc(Integer fulfillmentStatus) {
        return findFulfillmentStatus(fulfillmentStatus).getDesc();
    }

    public String getAftersaleStatusDesc(Integer aftersaleStatus) {
        return findAftersaleStatus(aftersaleStatus).getDesc();
    }

    private OrderState toOrderState(Integer statusCode) {
        return findByCode(OrderState.values(), statusCode, OrderState::getCode, OrderState.INVALID);
    }

    private OrderPaymentStatus findPaymentStatus(Integer code) {
        return findByCode(OrderPaymentStatus.values(), code, OrderPaymentStatus::getCode, OrderPaymentStatus.UNPAID);
    }

    private OrderFulfillmentStatus findFulfillmentStatus(Integer code) {
        return findByCode(OrderFulfillmentStatus.values(), code, OrderFulfillmentStatus::getCode, OrderFulfillmentStatus.UNFULFILLED);
    }

    private OrderAftersaleStatus findAftersaleStatus(Integer code) {
        return findByCode(OrderAftersaleStatus.values(), code, OrderAftersaleStatus::getCode, OrderAftersaleStatus.NONE);
    }

    private <E extends Enum<E>> E findByCode(E[] values,
                                             Integer code,
                                             Function<E, Integer> codeGetter,
                                             E defaultValue) {
        // 兜底查找，非法或空值都回落到约定默认值
        if (code == null) {
            return defaultValue;
        }
        for (E value : values) {
            if (code.equals(codeGetter.apply(value))) {
                return value;
            }
        }
        return defaultValue;
    }

    public record StatusMetadata(Integer displayStatus,
                                 String displayStatusDesc,
                                 Integer paymentStatus,
                                 Integer fulfillmentStatus,
                                 Integer aftersaleStatus) {
    }
}
