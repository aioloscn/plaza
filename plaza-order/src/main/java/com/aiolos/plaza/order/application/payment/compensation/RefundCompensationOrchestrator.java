package com.aiolos.plaza.order.application.payment.compensation;

import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.PaymentCompensationReasonCode;
import com.aiolos.plaza.enums.PaymentCompensationTaskStatus;
import com.aiolos.plaza.enums.PaymentCompensationType;
import com.aiolos.plaza.enums.RefundOrderStatus;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.mapper.PaymentCompensationTaskMapper;
import com.aiolos.plaza.mapper.RefundLogMapper;
import com.aiolos.plaza.mapper.RefundOrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.model.po.PaymentCompensationTask;
import com.aiolos.plaza.model.po.RefundOrder;
import com.aiolos.plaza.order.application.stock.reservation.StockReservationService;
import com.aiolos.plaza.order.domain.payment.compensation.PaymentCompensationTaskFactory;
import com.aiolos.plaza.order.domain.payment.compensation.RefundLogFactory;
import com.aiolos.plaza.order.domain.payment.compensation.RefundOrderFactory;
import com.aiolos.plaza.order.domain.order.status.OrderStateJudge;
import com.aiolos.plaza.order.domain.order.status.OrderStatusMetadataResolver;
import com.aiolos.plaza.order.application.order.status.ParentOrderRefreshAppService;
import com.aiolos.plaza.order.application.payment.gateway.AlipayGatewaySupport;
import com.aiolos.plaza.order.statemachine.config.OrderStateMachineService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 退款补偿编排服务
 * 负责退款回调落任务、退款执行、退款对账与退款结果收敛
 */
@Slf4j
@Component
public class RefundCompensationOrchestrator {

    private static final int[] RETRY_BACKOFF_MINUTES = {1, 5, 15, 30, 60, 120};

    private final PaymentCompensationTaskMapper paymentCompensationTaskMapper;
    private final RefundOrderMapper refundOrderMapper;
    private final RefundLogMapper refundLogMapper;
    private final ParentOrderMapper parentOrderMapper;
    private final OrderMapper orderMapper;
    private final PaymentCompensationTaskFactory paymentCompensationTaskFactory;
    private final RefundOrderFactory refundOrderFactory;
    private final RefundLogFactory refundLogFactory;
    private final OrderStateMachineService orderStateMachineService;
    private final ParentOrderRefreshAppService parentOrderRefreshAppService;
    private final OrderStateJudge orderStateJudge;
    private final OrderStatusMetadataResolver orderStatusMetadataResolver;
    private final AlipayGatewaySupport alipayGatewaySupport;
    private final StockReservationService stockReservationService;

    public RefundCompensationOrchestrator(PaymentCompensationTaskMapper paymentCompensationTaskMapper,
                                      RefundOrderMapper refundOrderMapper,
                                      RefundLogMapper refundLogMapper,
                                      ParentOrderMapper parentOrderMapper,
                                      OrderMapper orderMapper,
                                      PaymentCompensationTaskFactory paymentCompensationTaskFactory,
                                      RefundOrderFactory refundOrderFactory,
                                      RefundLogFactory refundLogFactory,
                                      OrderStateMachineService orderStateMachineService,
                                      ParentOrderRefreshAppService parentOrderRefreshAppService,
                                      OrderStateJudge orderStateJudge,
                                      OrderStatusMetadataResolver orderStatusMetadataResolver,
                                      AlipayGatewaySupport alipayGatewaySupport,
                                      StockReservationService stockReservationService) {
        this.paymentCompensationTaskMapper = paymentCompensationTaskMapper;
        this.refundOrderMapper = refundOrderMapper;
        this.refundLogMapper = refundLogMapper;
        this.parentOrderMapper = parentOrderMapper;
        this.orderMapper = orderMapper;
        this.paymentCompensationTaskFactory = paymentCompensationTaskFactory;
        this.refundOrderFactory = refundOrderFactory;
        this.refundLogFactory = refundLogFactory;
        this.orderStateMachineService = orderStateMachineService;
        this.parentOrderRefreshAppService = parentOrderRefreshAppService;
        this.orderStateJudge = orderStateJudge;
        this.orderStatusMetadataResolver = orderStatusMetadataResolver;
        this.alipayGatewaySupport = alipayGatewaySupport;
        this.stockReservationService = stockReservationService;
    }

    public void enqueueRefundTasks(int limit) {
        List<RefundOrder> refundOrders = refundOrderMapper.selectList(
                new LambdaQueryWrapper<RefundOrder>()
                        .in(RefundOrder::getStatus, Arrays.asList(
                                RefundOrderStatus.INIT.getCode(),
                                RefundOrderStatus.PROCESSING.getCode(),
                                RefundOrderStatus.FAILED.getCode()
                        ))
                        .and(w -> w.isNull(RefundOrder::getNextRetryTime).or().le(RefundOrder::getNextRetryTime, LocalDateTime.now()))
                        .orderByAsc(RefundOrder::getUpdateTime)
                        .last("LIMIT " + limit)
        );
        for (RefundOrder refundOrder : refundOrders) {
            PaymentCompensationType type = RefundOrderStatus.PROCESSING.getCode().equals(refundOrder.getStatus())
                    ? PaymentCompensationType.REFUND_RECONCILE
                    : PaymentCompensationType.REFUND_EXECUTE;
            upsertTask(
                    type,
                    buildRefundBusinessKey(type, refundOrder.getRefundRequestNo()),
                    refundOrder.getParentOrderSn(),
                    null,
                    refundOrder.getTradeNo(),
                    refundOrder.getRefundRequestNo(),
                    PaymentCompensationReasonCode.RECONCILE_DIFF,
                    LocalDateTime.now()
            );
        }
    }

    public String handleRefundNotify(Map<String, String> params) {
        if (!alipayGatewaySupport.verifySignature(params)) {
            log.error("支付宝退款回调验签失败");
            return "fail";
        }
        String refundRequestNo = params.get("out_request_no");
        if (!StringUtils.hasText(refundRequestNo)) {
            return "success";
        }
        RefundOrder refundOrder = loadRefundOrder(refundRequestNo);
        refundLogMapper.insert(refundLogFactory.buildRefundLog(
                refundRequestNo,
                "CALLBACK",
                "RECEIVED",
                String.valueOf(params),
                null,
                "收到退款回调"
        ));
        if (refundOrder == null) {
            return "success";
        }
        upsertTask(
                PaymentCompensationType.REFUND_RECONCILE,
                buildRefundBusinessKey(PaymentCompensationType.REFUND_RECONCILE, refundRequestNo),
                refundOrder.getParentOrderSn(),
                null,
                refundOrder.getTradeNo(),
                refundRequestNo,
                PaymentCompensationReasonCode.RECONCILE_DIFF,
                LocalDateTime.now()
        );
        return "success";
    }

    public void executeRefundExecuteTask(PaymentCompensationTask task) throws Exception {
        RefundOrder refundOrder = ensureRefundOrder(task);
        if (refundOrder == null) {
            manualTask(task, "退款单创建失败");
            return;
        }
        if (RefundOrderStatus.SUCCESS.getCode().equals(refundOrder.getStatus())) {
            markTaskSuccess(task, "SUCCESS", null);
            return;
        }
        ParentOrder parentOrder = loadParentOrder(refundOrder.getParentOrderSn());
        if (parentOrder == null) {
            manualTask(task, "父订单不存在，无法执行退款");
            return;
        }
        AlipayGatewaySupport.RefundExecuteResult result = alipayGatewaySupport.executeRefund(
                refundOrder.getParentOrderSn(),
                StringUtils.hasText(refundOrder.getTradeNo()) ? refundOrder.getTradeNo() : parentOrder.getTradeNo(),
                refundOrder.getRefundRequestNo(),
                refundOrder.getRefundAmount(),
                "支付补偿退款"
        );
        refundLogMapper.insert(refundLogFactory.buildRefundLog(
                refundOrder.getRefundRequestNo(),
                "APPLY",
                result.refundStatus(),
                result.requestPayload(),
                result.responsePayload(),
                result.message()
        ));
        if ("SUCCESS".equalsIgnoreCase(result.refundStatus())) {
            finalizeRefundSuccess(refundOrder);
            markTaskSuccess(task, result.refundStatus(), null);
            return;
        }
        if ("PROCESSING".equalsIgnoreCase(result.refundStatus())) {
            markRefundProcessing(refundOrder, result.message());
            upsertTask(
                    PaymentCompensationType.REFUND_RECONCILE,
                    buildRefundBusinessKey(PaymentCompensationType.REFUND_RECONCILE, refundOrder.getRefundRequestNo()),
                    refundOrder.getParentOrderSn(),
                    null,
                    refundOrder.getTradeNo(),
                    refundOrder.getRefundRequestNo(),
                    PaymentCompensationReasonCode.REFUND_STATUS_UNKNOWN,
                    LocalDateTime.now().plusMinutes(1)
            );
            markTaskSuccess(task, result.refundStatus(), result.message());
            return;
        }
        markRefundFailed(refundOrder, result.message());
        retryOrManual(task, result.message(), PaymentCompensationReasonCode.REFUND_EXECUTE_FAIL);
    }

    public void executeRefundReconcileTask(PaymentCompensationTask task) throws Exception {
        RefundOrder refundOrder = loadRefundOrder(task.getRefundRequestNo());
        if (refundOrder == null) {
            closeTask(task, "退款单不存在");
            return;
        }
        ParentOrder parentOrder = loadParentOrder(refundOrder.getParentOrderSn());
        if (parentOrder == null) {
            manualTask(task, "父订单不存在，无法对账");
            return;
        }
        AlipayGatewaySupport.RefundQueryResult result = alipayGatewaySupport.queryRefund(
                refundOrder.getParentOrderSn(),
                StringUtils.hasText(refundOrder.getTradeNo()) ? refundOrder.getTradeNo() : parentOrder.getTradeNo(),
                refundOrder.getRefundRequestNo()
        );
        refundLogMapper.insert(refundLogFactory.buildRefundLog(
                refundOrder.getRefundRequestNo(),
                "QUERY",
                result.refundStatus(),
                result.requestPayload(),
                result.responsePayload(),
                result.message()
        ));
        if ("SUCCESS".equalsIgnoreCase(result.refundStatus())) {
            finalizeRefundSuccess(refundOrder);
            markTaskSuccess(task, result.refundStatus(), result.message());
            return;
        }
        if ("PROCESSING".equalsIgnoreCase(result.refundStatus())) {
            markRefundProcessing(refundOrder, result.message());
            retryOrManual(task, result.message(), PaymentCompensationReasonCode.REFUND_STATUS_UNKNOWN);
            return;
        }
        markRefundFailed(refundOrder, result.message());
        retryOrManual(task, result.message(), PaymentCompensationReasonCode.RECONCILE_DIFF);
    }

    private RefundOrder ensureRefundOrder(PaymentCompensationTask task) {
        RefundOrder refundOrder = loadRefundOrder(task.getRefundRequestNo());
        if (refundOrder != null) {
            return refundOrder;
        }
        ParentOrder parentOrder = loadParentOrder(task.getParentOrderSn());
        if (parentOrder == null) {
            return null;
        }
        String refundRequestNo = StringUtils.hasText(task.getRefundRequestNo())
                ? task.getRefundRequestNo()
                : refundOrderFactory.buildRefundRequestNo(parentOrder.getParentOrderSn());
        refundOrder = refundOrderFactory.buildRefundOrder(parentOrder, refundRequestNo, PaymentCompensationReasonCode.REFUND_REQUEST_CREATED);
        refundOrderMapper.insert(refundOrder);
        return refundOrder;
    }

    private void finalizeRefundSuccess(RefundOrder refundOrder) {
        LocalDateTime now = LocalDateTime.now();
        refundOrderMapper.update(null, new LambdaUpdateWrapper<RefundOrder>()
                .set(RefundOrder::getStatus, RefundOrderStatus.SUCCESS.getCode())
                .set(RefundOrder::getFailReason, null)
                .set(RefundOrder::getRefundTime, now)
                .set(RefundOrder::getUpdateTime, now)
                .eq(RefundOrder::getRefundRequestNo, refundOrder.getRefundRequestNo()));

        List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, refundOrder.getParentOrderSn()));
        for (Order child : childOrders) {
            if (StringUtils.hasText(child.getReservationNo())) {
                stockReservationService.rollbackConfirmed(child.getReservationNo());
            }
            Order latest = orderMapper.selectById(child.getId());
            if (orderStateJudge.isRefunded(latest)) {
                continue;
            }
            if (orderStateJudge.isRefunding(latest)) {
                boolean accepted = orderStateMachineService.sendOrderEventWithDbState(
                        latest, OrderEvent.REFUND_SUCCESS, null, OrderExceptionEnum.ORDER_REFUND_FAIL);
                if (accepted) {
                    continue;
                }
            }
            orderMapper.update(null, orderStatusMetadataResolver.applyToOrderUpdate(
                            new LambdaUpdateWrapper<Order>(),
                            OrderState.REFUNDED.getCode()
                    )
                    .set(Order::getUpdateTime, now)
                    .eq(Order::getId, child.getId()));
        }
        parentOrderRefreshAppService.refresh(refundOrder.getParentOrderSn());
    }

    private void markRefundProcessing(RefundOrder refundOrder, String message) {
        refundOrderMapper.update(null, new LambdaUpdateWrapper<RefundOrder>()
                .set(RefundOrder::getStatus, RefundOrderStatus.PROCESSING.getCode())
                .set(RefundOrder::getFailReason, truncate(message))
                .set(RefundOrder::getNextRetryTime, LocalDateTime.now().plusMinutes(1))
                .set(RefundOrder::getUpdateTime, LocalDateTime.now())
                .eq(RefundOrder::getRefundRequestNo, refundOrder.getRefundRequestNo()));
    }

    private void markRefundFailed(RefundOrder refundOrder, String message) {
        int nextRetryCount = (refundOrder.getRetryCount() == null ? 0 : refundOrder.getRetryCount()) + 1;
        refundOrderMapper.update(null, new LambdaUpdateWrapper<RefundOrder>()
                .set(RefundOrder::getStatus, RefundOrderStatus.FAILED.getCode())
                .set(RefundOrder::getRetryCount, nextRetryCount)
                .set(RefundOrder::getFailReason, truncate(message))
                .set(RefundOrder::getNextRetryTime, LocalDateTime.now().plusMinutes(resolveBackoffMinutes(nextRetryCount)))
                .set(RefundOrder::getUpdateTime, LocalDateTime.now())
                .eq(RefundOrder::getRefundRequestNo, refundOrder.getRefundRequestNo()));
    }

    private void retryOrManual(PaymentCompensationTask task, String failReason, PaymentCompensationReasonCode reasonCode) {
        int nextRetryCount = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        Integer maxRetryCount = task.getMaxRetryCount();
        if (maxRetryCount != null && nextRetryCount >= maxRetryCount) {
            manualTask(task, failReason);
            return;
        }
        paymentCompensationTaskMapper.update(null, new LambdaUpdateWrapper<PaymentCompensationTask>()
                .set(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.RETRY.getCode())
                .set(PaymentCompensationTask::getRetryCount, nextRetryCount)
                .set(PaymentCompensationTask::getNextRetryTime, LocalDateTime.now().plusMinutes(resolveBackoffMinutes(nextRetryCount)))
                .set(PaymentCompensationTask::getReasonCode, reasonCode.getCode())
                .set(PaymentCompensationTask::getFailReason, truncate(failReason))
                .set(PaymentCompensationTask::getUpdateTime, LocalDateTime.now())
                .eq(PaymentCompensationTask::getId, task.getId())
                .eq(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.PROCESSING.getCode()));
    }

    private void manualTask(PaymentCompensationTask task, String failReason) {
        paymentCompensationTaskMapper.update(null, new LambdaUpdateWrapper<PaymentCompensationTask>()
                .set(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.MANUAL_PENDING.getCode())
                .set(PaymentCompensationTask::getReasonCode, PaymentCompensationReasonCode.MANUAL_REQUIRED.getCode())
                .set(PaymentCompensationTask::getFailReason, truncate(failReason))
                .set(PaymentCompensationTask::getUpdateTime, LocalDateTime.now())
                .eq(PaymentCompensationTask::getId, task.getId())
                .eq(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.PROCESSING.getCode()));

        RefundOrder refundOrder = loadRefundOrder(task.getRefundRequestNo());
        if (refundOrder != null) {
            refundOrderMapper.update(null, new LambdaUpdateWrapper<RefundOrder>()
                    .set(RefundOrder::getStatus, RefundOrderStatus.MANUAL_PENDING.getCode())
                    .set(RefundOrder::getFailReason, truncate(failReason))
                    .set(RefundOrder::getUpdateTime, LocalDateTime.now())
                    .eq(RefundOrder::getRefundRequestNo, refundOrder.getRefundRequestNo()));
            markOrdersRefundFailed(refundOrder.getParentOrderSn());
        }
    }

    private void markTaskSuccess(PaymentCompensationTask task, String thirdPartyStatus, String message) {
        paymentCompensationTaskMapper.update(null, new LambdaUpdateWrapper<PaymentCompensationTask>()
                .set(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.SUCCESS.getCode())
                .set(PaymentCompensationTask::getThirdPartyStatus, thirdPartyStatus)
                .set(PaymentCompensationTask::getFailReason, truncate(message))
                .set(PaymentCompensationTask::getUpdateTime, LocalDateTime.now())
                .eq(PaymentCompensationTask::getId, task.getId())
                .eq(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.PROCESSING.getCode()));
    }

    private void closeTask(PaymentCompensationTask task, String message) {
        paymentCompensationTaskMapper.update(null, new LambdaUpdateWrapper<PaymentCompensationTask>()
                .set(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.CLOSED.getCode())
                .set(PaymentCompensationTask::getFailReason, truncate(message))
                .set(PaymentCompensationTask::getUpdateTime, LocalDateTime.now())
                .eq(PaymentCompensationTask::getId, task.getId())
                .eq(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.PROCESSING.getCode()));
    }

    private void upsertTask(PaymentCompensationType type,
                            String businessKey,
                            String parentOrderSn,
                            String orderSn,
                            String tradeNo,
                            String refundRequestNo,
                            PaymentCompensationReasonCode reasonCode,
                            LocalDateTime nextRetryTime) {
        PaymentCompensationTask existing = paymentCompensationTaskMapper.selectOne(
                new LambdaQueryWrapper<PaymentCompensationTask>()
                        .eq(PaymentCompensationTask::getBusinessKey, businessKey)
                        .ne(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.SUCCESS.getCode())
                        .ne(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.CLOSED.getCode())
                        .last("LIMIT 1")
        );
        if (existing != null) {
            paymentCompensationTaskMapper.update(null, new LambdaUpdateWrapper<PaymentCompensationTask>()
                    .set(PaymentCompensationTask::getTradeNo, tradeNo)
                    .set(PaymentCompensationTask::getRefundRequestNo, refundRequestNo)
                    .set(PaymentCompensationTask::getReasonCode, reasonCode.getCode())
                    .set(PaymentCompensationTask::getNextRetryTime, nextRetryTime)
                    .set(PaymentCompensationTask::getUpdateTime, LocalDateTime.now())
                    .eq(PaymentCompensationTask::getId, existing.getId()));
            return;
        }
        PaymentCompensationTask task = paymentCompensationTaskFactory.buildTask(
                type, businessKey, parentOrderSn, orderSn, tradeNo, refundRequestNo, reasonCode, nextRetryTime);
        paymentCompensationTaskMapper.insert(task);
    }

    private ParentOrder loadParentOrder(String parentOrderSn) {
        if (!StringUtils.hasText(parentOrderSn)) {
            return null;
        }
        return parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, parentOrderSn)
                .last("LIMIT 1"));
    }

    private RefundOrder loadRefundOrder(String refundRequestNo) {
        if (!StringUtils.hasText(refundRequestNo)) {
            return null;
        }
        return refundOrderMapper.selectOne(new LambdaQueryWrapper<RefundOrder>()
                .eq(RefundOrder::getRefundRequestNo, refundRequestNo)
                .last("LIMIT 1"));
    }

    private long resolveBackoffMinutes(int retryCount) {
        int index = Math.max(0, Math.min(retryCount - 1, RETRY_BACKOFF_MINUTES.length - 1));
        return RETRY_BACKOFF_MINUTES[index];
    }

    private String buildRefundBusinessKey(PaymentCompensationType type, String refundRequestNo) {
        return type.name().toLowerCase() + ":" + refundRequestNo;
    }

    private void markOrdersRefundFailed(String parentOrderSn) {
        if (!StringUtils.hasText(parentOrderSn)) {
            return;
        }
        List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, parentOrderSn));
        LocalDateTime now = LocalDateTime.now();
        for (Order child : childOrders) {
            Order latest = orderMapper.selectById(child.getId());
            if (orderStateJudge.isRefunded(latest)) {
                continue;
            }
            if (orderStateJudge.isRefunding(latest)) {
                boolean accepted = orderStateMachineService.sendOrderEventWithDbState(
                        latest, OrderEvent.REFUND_FAIL, null, OrderExceptionEnum.ORDER_REFUND_FAIL);
                if (accepted) {
                    continue;
                }
            }
            orderMapper.update(null, orderStatusMetadataResolver.applyToOrderUpdate(
                            new LambdaUpdateWrapper<Order>(),
                            OrderState.REFUND_FAILED.getCode()
                    )
                    .set(Order::getUpdateTime, now)
                    .eq(Order::getId, child.getId()));
        }
        parentOrderRefreshAppService.refresh(parentOrderSn);
    }

    private String truncate(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
