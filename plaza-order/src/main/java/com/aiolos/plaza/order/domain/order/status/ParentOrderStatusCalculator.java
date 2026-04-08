package com.aiolos.plaza.order.domain.order.status;

import com.aiolos.plaza.enums.OrderAftersaleStatus;
import com.aiolos.plaza.enums.OrderFulfillmentStatus;
import com.aiolos.plaza.enums.OrderPaymentStatus;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.model.po.Order;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 父单状态统一按子单集合聚合，避免不同服务各自维护一份 if/else 规则
 */
@Component
public class ParentOrderStatusCalculator {

    @Resource
    private OrderStatusMetadataResolver orderStatusMetadataResolver;

    /**
     * 仅返回父单展示态，兼容仍然只依赖旧 status 字段的调用方
     */
    public Integer calculate(List<Order> childOrders) {
        ParentOrderStatusSnapshot snapshot = calculateSnapshot(childOrders);
        return snapshot == null ? null : snapshot.displayStatus();
    }

    /**
     * 统一聚合父单的展示态、支付态、履约态和售后态
     */
    public ParentOrderStatusSnapshot calculateSnapshot(List<Order> childOrders) {
        if (childOrders == null || childOrders.isEmpty()) {
            return null;
        }
        // 展示态继续复用历史 status 聚合口径，多维状态则单独按支付/履约/售后拆开计算
        Integer displayStatus = calculateByStatuses(childOrders.stream().map(Order::getStatus).toList());
        Integer paymentStatus = aggregatePaymentStatus(childOrders);
        Integer fulfillmentStatus = aggregateFulfillmentStatus(childOrders);
        Integer aftersaleStatus = aggregateAftersaleStatus(childOrders);
        return new ParentOrderStatusSnapshot(displayStatus, paymentStatus, fulfillmentStatus, aftersaleStatus);
    }

    /**
     * 历史展示态聚合规则，保留为兼容层，避免前台状态筛选一次性全部失效
     */
    public Integer calculateByStatuses(List<Integer> childStatuses) {
        if (childStatuses == null || childStatuses.isEmpty()) {
            return null;
        }
        // 这里是“兼容旧前台展示态”的优先级规则，不等同于真实支付/履约/售后三维状态
        boolean allClosed = childStatuses.stream().allMatch(s -> OrderState.CLOSED.getCode().equals(s));
        if (allClosed) {
            return OrderState.CLOSED.getCode();
        }
        boolean allCompleted = childStatuses.stream().allMatch(s -> OrderState.COMPLETED.getCode().equals(s));
        if (allCompleted) {
            return OrderState.COMPLETED.getCode();
        }
        boolean allDeliveredOrCompleted = childStatuses.stream().allMatch(s ->
                OrderState.DELIVERED.getCode().equals(s) || OrderState.COMPLETED.getCode().equals(s));
        boolean hasDelivered = childStatuses.stream().anyMatch(s -> OrderState.DELIVERED.getCode().equals(s));
        if (allDeliveredOrCompleted && hasDelivered) {
            return OrderState.DELIVERED.getCode();
        }
        boolean hasReserving = childStatuses.stream().anyMatch(s -> OrderState.RESERVING.getCode().equals(s));
        boolean hasCreated = childStatuses.stream().anyMatch(s -> OrderState.CREATED.getCode().equals(s));
        boolean hasPaid = childStatuses.stream().anyMatch(s -> OrderState.PAID.getCode().equals(s));
        boolean hasPaying = childStatuses.stream().anyMatch(s -> OrderState.PAYING.getCode().equals(s));
        boolean hasClosing = childStatuses.stream().anyMatch(s -> OrderState.CLOSING.getCode().equals(s));
        boolean hasPayRecovering = childStatuses.stream().anyMatch(s -> OrderState.PAY_RECOVERING.getCode().equals(s));
        boolean hasRefunding = childStatuses.stream().anyMatch(s -> OrderState.REFUNDING.getCode().equals(s));
        boolean hasRefundFailed = childStatuses.stream().anyMatch(s -> OrderState.REFUND_FAILED.getCode().equals(s));
        boolean allRefunded = childStatuses.stream().allMatch(s -> OrderState.REFUNDED.getCode().equals(s));
        boolean hasPaidOrAfter = childStatuses.stream().anyMatch(s ->
                OrderState.PAID.getCode().equals(s)
                        || OrderState.DELIVERED.getCode().equals(s)
                        || OrderState.COMPLETED.getCode().equals(s));
        // 退款相关状态优先级最高，避免父单仍展示成 paid/delivered 而掩盖真实售后风险
        if (hasRefunding) {
            return OrderState.REFUNDING.getCode();
        }
        if (allRefunded) {
            return OrderState.REFUNDED.getCode();
        }
        if (hasRefundFailed) {
            return OrderState.REFUND_FAILED.getCode();
        }
        if (!hasCreated && hasPaid) {
            return OrderState.PAID.getCode();
        }
        if (!hasPaidOrAfter && hasPayRecovering) {
            return OrderState.PAY_RECOVERING.getCode();
        }
        if (!hasPaidOrAfter && hasReserving) {
            return OrderState.RESERVING.getCode();
        }
        if (!hasPaidOrAfter && hasClosing) {
            return OrderState.CLOSING.getCode();
        }
        if (!hasPaidOrAfter && hasPaying) {
            return OrderState.PAYING.getCode();
        }
        return OrderState.CREATED.getCode();
    }

    /**
     * 支付维度优先看退款链路，其次看支付中/补偿中，再判断是否部分支付
     */
    private Integer aggregatePaymentStatus(List<Order> childOrders) {
        List<Integer> statuses = childOrders.stream()
                .map(order -> order.getPaymentStatus() != null
                        ? order.getPaymentStatus()
                        : orderStatusMetadataResolver.resolve(order.getStatus()).paymentStatus())
                .toList();
        // 支付维度先判断退款链路，再判断支付中/补偿中，最后才判断 paid / partial paid / unpaid
        if (statuses.stream().allMatch(s -> OrderPaymentStatus.REFUNDED.getCode().equals(s))) {
            return OrderPaymentStatus.REFUNDED.getCode();
        }
        if (statuses.stream().anyMatch(s -> OrderPaymentStatus.REFUNDING.getCode().equals(s))) {
            return OrderPaymentStatus.REFUNDING.getCode();
        }
        if (statuses.stream().anyMatch(s -> OrderPaymentStatus.REFUND_FAILED.getCode().equals(s))) {
            return OrderPaymentStatus.REFUND_FAILED.getCode();
        }
        if (statuses.stream().anyMatch(s -> OrderPaymentStatus.COMPENSATING.getCode().equals(s))) {
            return OrderPaymentStatus.COMPENSATING.getCode();
        }
        if (statuses.stream().anyMatch(s -> OrderPaymentStatus.PAYING.getCode().equals(s))) {
            return OrderPaymentStatus.PAYING.getCode();
        }
        boolean hasPaid = statuses.stream().anyMatch(s -> OrderPaymentStatus.PAID.getCode().equals(s));
        boolean hasUnpaid = statuses.stream().anyMatch(s -> OrderPaymentStatus.UNPAID.getCode().equals(s));
        if (hasPaid && hasUnpaid) {
            return OrderPaymentStatus.PARTIAL_PAID.getCode();
        }
        if (hasPaid) {
            return OrderPaymentStatus.PAID.getCode();
        }
        return OrderPaymentStatus.UNPAID.getCode();
    }

    /**
     * 履约维度区分待履约、锁库存、待发货、部分发货、已发货、已完成和已关闭
     */
    private Integer aggregateFulfillmentStatus(List<Order> childOrders) {
        List<Integer> statuses = childOrders.stream()
                .map(order -> order.getFulfillmentStatus() != null
                        ? order.getFulfillmentStatus()
                        : orderStatusMetadataResolver.resolve(order.getStatus()).fulfillmentStatus())
                .toList();
        // 履约维度允许“部分发货”这种混合态，避免父单在子单一部分已发货时只能粗暴落到 delivered/to_deliver
        if (statuses.stream().allMatch(s -> OrderFulfillmentStatus.CLOSED.getCode().equals(s))) {
            return OrderFulfillmentStatus.CLOSED.getCode();
        }
        if (statuses.stream().allMatch(s -> OrderFulfillmentStatus.COMPLETED.getCode().equals(s))) {
            return OrderFulfillmentStatus.COMPLETED.getCode();
        }
        boolean hasDeliveredLike = statuses.stream().anyMatch(s ->
                OrderFulfillmentStatus.DELIVERED.getCode().equals(s)
                        || OrderFulfillmentStatus.COMPLETED.getCode().equals(s));
        boolean hasUndeliveredLike = statuses.stream().anyMatch(s ->
                OrderFulfillmentStatus.TO_DELIVER.getCode().equals(s)
                        || OrderFulfillmentStatus.UNFULFILLED.getCode().equals(s)
                        || OrderFulfillmentStatus.RESERVING.getCode().equals(s));
        if (hasDeliveredLike && hasUndeliveredLike) {
            return OrderFulfillmentStatus.PARTIALLY_DELIVERED.getCode();
        }
        if (statuses.stream().anyMatch(s -> OrderFulfillmentStatus.DELIVERED.getCode().equals(s))) {
            return OrderFulfillmentStatus.DELIVERED.getCode();
        }
        if (statuses.stream().anyMatch(s -> OrderFulfillmentStatus.TO_DELIVER.getCode().equals(s))) {
            return OrderFulfillmentStatus.TO_DELIVER.getCode();
        }
        if (statuses.stream().anyMatch(s -> OrderFulfillmentStatus.RESERVING.getCode().equals(s))) {
            return OrderFulfillmentStatus.RESERVING.getCode();
        }
        return OrderFulfillmentStatus.UNFULFILLED.getCode();
    }

    /**
     * 售后维度单独聚合，避免退款态继续挤占主展示态的语义空间
     */
    private Integer aggregateAftersaleStatus(List<Order> childOrders) {
        List<Integer> statuses = childOrders.stream()
                .map(order -> order.getAftersaleStatus() != null
                        ? order.getAftersaleStatus()
                        : orderStatusMetadataResolver.resolve(order.getStatus()).aftersaleStatus())
                .toList();
        // 售后维度与展示态解耦后，父单可以同时表达“已支付/已发货”和“部分退款”等组合语义
        if (statuses.stream().allMatch(s -> OrderAftersaleStatus.NONE.getCode().equals(s))) {
            return OrderAftersaleStatus.NONE.getCode();
        }
        if (statuses.stream().allMatch(s -> OrderAftersaleStatus.REFUNDED.getCode().equals(s))) {
            return OrderAftersaleStatus.REFUNDED.getCode();
        }
        if (statuses.stream().anyMatch(s -> OrderAftersaleStatus.REFUNDING.getCode().equals(s))) {
            return OrderAftersaleStatus.REFUNDING.getCode();
        }
        boolean hasRefunded = statuses.stream().anyMatch(s -> OrderAftersaleStatus.REFUNDED.getCode().equals(s));
        boolean hasNone = statuses.stream().anyMatch(s -> OrderAftersaleStatus.NONE.getCode().equals(s));
        if (hasRefunded && hasNone) {
            return OrderAftersaleStatus.PARTIALLY_REFUNDED.getCode();
        }
        if (statuses.stream().anyMatch(s -> OrderAftersaleStatus.REFUND_FAILED.getCode().equals(s))) {
            return OrderAftersaleStatus.REFUND_FAILED.getCode();
        }
        return OrderAftersaleStatus.PARTIALLY_REFUNDED.getCode();
    }

    /**
     * 父单状态聚合快照
     */
    public record ParentOrderStatusSnapshot(Integer displayStatus,
                                            Integer paymentStatus,
                                            Integer fulfillmentStatus,
                                            Integer aftersaleStatus) {
    }
}
