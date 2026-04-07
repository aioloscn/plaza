package com.aiolos.plaza.order.application.payment;
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
import com.aiolos.plaza.order.coreflow.inventory.service.OrderInventoryService;
import com.aiolos.plaza.order.domain.payment.PaymentCompensationTaskFactory;
import com.aiolos.plaza.order.domain.status.OrderStatusMetadataResolver;
import com.aiolos.plaza.order.domain.status.ParentStatusDomainService;
import com.aiolos.plaza.order.workflow.payment.AlipayGatewaySupport;
import com.aiolos.plaza.order.workflow.payment.PaymentNotifyCommand;
import com.aiolos.plaza.order.workflow.payment.PaymentNotifyContext;
import com.aiolos.plaza.order.workflow.payment.PaymentNotifyPrecheck;
import com.aiolos.plaza.order.workflow.payment.PaymentNotifyRouter;
import com.aiolos.plaza.order.workflow.payment.PaymentNotifyScenario;
import com.aiolos.plaza.order.workflow.payment.PaymentNotifySupport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 支付补偿任务执行器
 */
@Slf4j
@Service
public class PaymentCompensationTaskService {

    private static final int BATCH_SIZE = 50;
    private static final long CLAIM_TIMEOUT_SECONDS = 300;
    private static final int[] RETRY_BACKOFF_MINUTES = {1, 5, 15, 30, 60, 120};

    private final PaymentCompensationTaskMapper paymentCompensationTaskMapper;
    private final RefundOrderMapper refundOrderMapper;
    private final RefundLogMapper refundLogMapper;
    private final ParentOrderMapper parentOrderMapper;
    private final OrderMapper orderMapper;
    private final PaymentCompensationTaskFactory taskFactory;
    private final PaymentNotifySupport paymentNotifySupport;
    private final PaymentNotifyRouter paymentNotifyRouter;
    private final ParentStatusDomainService parentStatusDomainService;
    private final OrderStatusMetadataResolver orderStatusMetadataResolver;
    private final AlipayGatewaySupport alipayGatewaySupport;
    private final OrderInventoryService orderInventoryService;

    public PaymentCompensationTaskService(PaymentCompensationTaskMapper paymentCompensationTaskMapper,
                                          RefundOrderMapper refundOrderMapper,
                                          RefundLogMapper refundLogMapper,
                                          ParentOrderMapper parentOrderMapper,
                                          OrderMapper orderMapper,
                                          PaymentCompensationTaskFactory taskFactory,
                                          PaymentNotifySupport paymentNotifySupport,
                                          PaymentNotifyRouter paymentNotifyRouter,
                                          ParentStatusDomainService parentStatusDomainService,
                                          OrderStatusMetadataResolver orderStatusMetadataResolver,
                                          AlipayGatewaySupport alipayGatewaySupport,
                                          OrderInventoryService orderInventoryService) {
        this.paymentCompensationTaskMapper = paymentCompensationTaskMapper;
        this.refundOrderMapper = refundOrderMapper;
        this.refundLogMapper = refundLogMapper;
        this.parentOrderMapper = parentOrderMapper;
        this.orderMapper = orderMapper;
        this.taskFactory = taskFactory;
        this.paymentNotifySupport = paymentNotifySupport;
        this.paymentNotifyRouter = paymentNotifyRouter;
        this.parentStatusDomainService = parentStatusDomainService;
        this.orderStatusMetadataResolver = orderStatusMetadataResolver;
        this.alipayGatewaySupport = alipayGatewaySupport;
        this.orderInventoryService = orderInventoryService;
    }

    public void processReadyTasks() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime claimDeadline = now.minusSeconds(CLAIM_TIMEOUT_SECONDS);
        // 任务表既承担“待执行/待重试”任务，也负责回收长时间卡在 processing 的僵尸任务
        List<PaymentCompensationTask> tasks = paymentCompensationTaskMapper.selectList(
                new LambdaQueryWrapper<PaymentCompensationTask>()
                        .and(w -> w.in(PaymentCompensationTask::getStatus,
                                        PaymentCompensationTaskStatus.INIT.getCode(),
                                        PaymentCompensationTaskStatus.RETRY.getCode())
                                .or()
                                .eq(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.PROCESSING.getCode())
                                .le(PaymentCompensationTask::getUpdateTime, claimDeadline))
                        .and(w -> w.isNull(PaymentCompensationTask::getNextRetryTime)
                                .or()
                                .le(PaymentCompensationTask::getNextRetryTime, now))
                        .orderByAsc(PaymentCompensationTask::getNextRetryTime)
                        .orderByAsc(PaymentCompensationTask::getCreateTime)
                        .last("LIMIT " + BATCH_SIZE)
        );
        for (PaymentCompensationTask task : tasks) {
            if (claimTask(task, claimDeadline) == 0) {
                continue;
            }
            executeClaimedTask(task);
        }
    }

    public void enqueueReconcileTasks(int limit) {
        LocalDateTime paymentThreshold = LocalDateTime.now().minusMinutes(5);
        // 长时间停在支付前后中间态的父单，不再被动等回调，而是主动补一条查单任务兜底
        List<ParentOrder> pendingParents = parentOrderMapper.selectList(
                new LambdaQueryWrapper<ParentOrder>()
                        .in(ParentOrder::getStatus, Arrays.asList(
                                OrderState.CREATED.getCode(),
                                OrderState.PAYING.getCode(),
                                OrderState.CLOSING.getCode(),
                                OrderState.PAY_RECOVERING.getCode(),
                                OrderState.REFUNDING.getCode()
                        ))
                        .le(ParentOrder::getUpdateTime, paymentThreshold)
                        .orderByAsc(ParentOrder::getUpdateTime)
                        .last("LIMIT " + limit)
        );
        for (ParentOrder parentOrder : pendingParents) {
            upsertTask(
                    PaymentCompensationType.PAYMENT_QUERY,
                    buildPaymentQueryBusinessKey(parentOrder.getParentOrderSn()),
                    parentOrder.getParentOrderSn(),
                    null,
                    parentOrder.getTradeNo(),
                    null,
                    PaymentCompensationReasonCode.RECONCILE_DIFF,
                    LocalDateTime.now()
            );
        }

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
            // 退款单处于处理中时走对账查询；仍未发起或已失败时走执行重试
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
        refundLogMapper.insert(taskFactory.buildRefundLog(
                refundRequestNo,
                "CALLBACK",
                "RECEIVED",
                String.valueOf(params),
                null,
                "收到退款回调"
        ));
        // 回调本身不直接改退款终态，而是统一转成 refund reconcile 任务，保持执行入口单一
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

    public void executeTaskIfReady(Long taskId) {
        if (taskId == null) {
            return;
        }
        PaymentCompensationTask task = paymentCompensationTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        LocalDateTime claimDeadline = LocalDateTime.now().minusSeconds(CLAIM_TIMEOUT_SECONDS);
        if (claimTask(task, claimDeadline) > 0) {
            executeClaimedTask(task);
        }
    }

    public void executeRefundTaskIfReady(String refundRequestNo) {
        if (!StringUtils.hasText(refundRequestNo)) {
            return;
        }
        PaymentCompensationTask task = paymentCompensationTaskMapper.selectOne(
                new LambdaQueryWrapper<PaymentCompensationTask>()
                        .eq(PaymentCompensationTask::getRefundRequestNo, refundRequestNo)
                        .in(PaymentCompensationTask::getCompensationType,
                                PaymentCompensationType.REFUND_EXECUTE.getCode(),
                                PaymentCompensationType.REFUND_RECONCILE.getCode())
                        .ne(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.SUCCESS.getCode())
                        .ne(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.CLOSED.getCode())
                        .orderByDesc(PaymentCompensationTask::getUpdateTime)
                        .last("LIMIT 1"));
        if (task == null) {
            return;
        }
        executeTaskIfReady(task.getId());
    }

    private int claimTask(PaymentCompensationTask task, LocalDateTime claimDeadline) {
        // 抢占更新是并发执行的核心保护，只有把任务切到 processing 的线程才能继续往下跑
        return paymentCompensationTaskMapper.update(null, new LambdaUpdateWrapper<PaymentCompensationTask>()
                .set(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.PROCESSING.getCode())
                .set(PaymentCompensationTask::getUpdateTime, LocalDateTime.now())
                .eq(PaymentCompensationTask::getId, task.getId())
                .and(w -> w.in(PaymentCompensationTask::getStatus,
                                PaymentCompensationTaskStatus.INIT.getCode(),
                                PaymentCompensationTaskStatus.RETRY.getCode())
                        .or()
                        .eq(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.PROCESSING.getCode())
                        .le(PaymentCompensationTask::getUpdateTime, claimDeadline)));
    }

    private void executeClaimedTask(PaymentCompensationTask task) {
        try {
            PaymentCompensationType type = toTaskType(task.getCompensationType());
            // 补偿类型分流后，每条链路都能独立演进自己的重试和状态收敛策略。
            switch (type) {
                case PAYMENT_QUERY -> executePaymentQueryTask(task);
                case REFUND_EXECUTE -> executeRefundExecuteTask(task);
                case REFUND_RECONCILE -> executeRefundReconcileTask(task);
            }
        } catch (Exception e) {
            log.error("执行支付补偿任务异常，taskNo={}", task.getTaskNo(), e);
            retryOrManual(task, e.getMessage(), PaymentCompensationReasonCode.MANUAL_REQUIRED);
        }
    }

    private void executePaymentQueryTask(PaymentCompensationTask task) throws Exception {
        ParentOrder parentOrder = loadParentOrder(task.getParentOrderSn());
        if (parentOrder == null) {
            closeTask(task, "父订单不存在");
            return;
        }
        // 支付查询补偿的目标不是简单“查一下”，而是把缺失的支付回调重新投递回既有支付编排链路。
        AlipayGatewaySupport.TradeQueryResult queryResult =
                alipayGatewaySupport.queryTrade(parentOrder.getParentOrderSn(), parentOrder.getTradeNo());
        if ("PAID".equalsIgnoreCase(queryResult.tradeStatus())) {
            PaymentNotifyCommand command = new PaymentNotifyCommand(
                    parentOrder.getParentOrderSn(),
                    "TRADE_SUCCESS",
                    queryResult.totalAmount() == null ? parentOrder.getPayAmount() : queryResult.totalAmount(),
                    queryResult.tradeNo(),
                    queryResult.buyerId()
            );
            PaymentNotifyPrecheck precheck = paymentNotifySupport.inspectChildOrders(parentOrder.getParentOrderSn());
            PaymentNotifyScenario scenario = paymentNotifyRouter.route(parentOrder, command, precheck, paymentNotifySupport);
            String result = scenario.handle(new PaymentNotifyContext(parentOrder, command, precheck), paymentNotifySupport);
            if ("success".equalsIgnoreCase(result)) {
                markTaskSuccess(task, queryResult.tradeStatus(), null);
                return;
            }
            // 三方明确已支付，但本地编排没有成功收敛，是最危险的冲突场景之一，需要继续补偿。
            retryOrManual(task, "支付查询补偿返回失败", PaymentCompensationReasonCode.PAYMENT_STATUS_CONFLICT);
            return;
        }
        if ("UNPAID".equalsIgnoreCase(queryResult.tradeStatus()) || "CLOSED".equalsIgnoreCase(queryResult.tradeStatus())) {
            // 如果本地仍停留在支付前状态，说明这笔支付并未真正完成，可以直接收口关闭任务。
            if (isPrePayStatus(parentOrder.getStatus()) || OrderState.CLOSED.getCode().equals(parentOrder.getStatus())) {
                closeTask(task, "三方支付未成功，无需恢复");
                return;
            }
            // 本地已进入支付后状态但三方显示未支付/已关闭，自动补偿风险高，直接转人工。
            manualTask(task, "本地订单已进入支付后状态，但三方返回未支付/已关闭");
            return;
        }
        retryOrManual(task, queryResult.message(), PaymentCompensationReasonCode.PAYMENT_CALLBACK_TIMEOUT);
    }

    private void executeRefundExecuteTask(PaymentCompensationTask task) throws Exception {
        RefundOrder refundOrder = ensureRefundOrder(task);
        if (refundOrder == null) {
            manualTask(task, "退款单创建失败");
            return;
        }
        if (RefundOrderStatus.SUCCESS.getCode().equals(refundOrder.getStatus())) {
            markTaskSuccess(task, "SUCCESS", null);
            return;
        }
        // 退款执行任务负责第一次发起退款，也负责退款失败后的自动重试。
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
        refundLogMapper.insert(taskFactory.buildRefundLog(
                refundOrder.getRefundRequestNo(),
                "APPLY",
                result.refundStatus(),
                result.requestPayload(),
                result.responsePayload(),
                result.message()
        ));
        if ("SUCCESS".equalsIgnoreCase(result.refundStatus())) {
            finalizeRefundSuccess(refundOrder, parentOrder);
            markTaskSuccess(task, result.refundStatus(), null);
            return;
        }
        if ("PROCESSING".equalsIgnoreCase(result.refundStatus())) {
            markRefundProcessing(refundOrder, result.message());
            // 三方受理但未最终成功时，转由 refund reconcile 任务继续查结果，避免同步接口长时间阻塞。
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

    private void executeRefundReconcileTask(PaymentCompensationTask task) throws Exception {
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
        // 对账任务把三方退款查询结果沉淀到 refund_log，便于后续人工排障时还原过程。
        refundLogMapper.insert(taskFactory.buildRefundLog(
                refundOrder.getRefundRequestNo(),
                "QUERY",
                result.refundStatus(),
                result.requestPayload(),
                result.responsePayload(),
                result.message()
        ));
        if ("SUCCESS".equalsIgnoreCase(result.refundStatus())) {
            finalizeRefundSuccess(refundOrder, parentOrder);
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
                : taskFactory.buildRefundRequestNo(parentOrder.getParentOrderSn());
        // 允许任务反向补建退款单，这样旧数据或异常中断场景也能重新进入完整退款子域。
        refundOrder = taskFactory.buildRefundOrder(parentOrder, refundRequestNo, PaymentCompensationReasonCode.REFUND_REQUEST_CREATED);
        refundOrderMapper.insert(refundOrder);
        return refundOrder;
    }

    private void finalizeRefundSuccess(RefundOrder refundOrder, ParentOrder parentOrder) {
        LocalDateTime now = LocalDateTime.now();
        // 退款成功后要同时收敛三类状态：退款单、库存确认结果、父子订单展示态/三维状态。
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
                // 已确认库存需要做回滚，避免“退款成功但库存仍被确认消耗”。
                orderInventoryService.rollbackConfirmed(child.getReservationNo());
            }
            Order latest = orderMapper.selectById(child.getId());
            if (latest == null || OrderState.REFUNDED.getCode().equals(latest.getStatus())) {
                continue;
            }
            if (OrderState.REFUNDING.getCode().equals(latest.getStatus())) {
                boolean accepted = paymentNotifySupport.sendOrderEventWithDbState(
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
        parentStatusDomainService.recomputeParentOrderStatus(refundOrder.getParentOrderSn());
    }

    private void markRefundProcessing(RefundOrder refundOrder, String message) {
        // processing 是明确的中间态，后续由 refund reconcile 任务继续追踪最终结果。
        refundOrderMapper.update(null, new LambdaUpdateWrapper<RefundOrder>()
                .set(RefundOrder::getStatus, RefundOrderStatus.PROCESSING.getCode())
                .set(RefundOrder::getFailReason, truncate(message))
                .set(RefundOrder::getNextRetryTime, LocalDateTime.now().plusMinutes(1))
                .set(RefundOrder::getUpdateTime, LocalDateTime.now())
                .eq(RefundOrder::getRefundRequestNo, refundOrder.getRefundRequestNo()));
    }

    private void markRefundFailed(RefundOrder refundOrder, String message) {
        int nextRetryCount = (refundOrder.getRetryCount() == null ? 0 : refundOrder.getRetryCount()) + 1;
        // 退款单自身也记录重试次数和下一次时间，便于从业务视角直接看到退款执行健康度。
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
        // 自动补偿只在有限次数内进行，避免不确定状态无限空转消耗资源。
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
        // 人工介入不是只改任务状态；若已进入退款域，还要把退款单与订单状态一起推进到可观测失败态。
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
        // 任务幂等以 businessKey 为核心，重复触发时只刷新关键上下文，不重复插入新任务。
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
        PaymentCompensationTask task = taskFactory.buildTask(
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

    private PaymentCompensationType toTaskType(Integer code) {
        for (PaymentCompensationType value : PaymentCompensationType.values()) {
            if (value.getCode().equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知补偿任务类型: " + code);
    }

    private boolean isPrePayStatus(Integer status) {
        return OrderState.CREATED.getCode().equals(status)
                || OrderState.PAYING.getCode().equals(status)
                || OrderState.CLOSING.getCode().equals(status)
                || OrderState.PAY_RECOVERING.getCode().equals(status);
    }

    private long resolveBackoffMinutes(int retryCount) {
        // 退避时间统一集中管理，避免不同补偿链路各自定义一套重试节奏。
        int index = Math.max(0, Math.min(retryCount - 1, RETRY_BACKOFF_MINUTES.length - 1));
        return RETRY_BACKOFF_MINUTES[index];
    }

    private String buildPaymentQueryBusinessKey(String parentOrderSn) {
        return "payment-query:" + parentOrderSn;
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
        // 转人工时同步把订单推进到 refund failed，避免业务侧只看到“退款中”而不知道已卡死。
        for (Order child : childOrders) {
            Order latest = orderMapper.selectById(child.getId());
            if (latest == null || OrderState.REFUNDED.getCode().equals(latest.getStatus())) {
                continue;
            }
            if (OrderState.REFUNDING.getCode().equals(latest.getStatus())) {
                boolean accepted = paymentNotifySupport.sendOrderEventWithDbState(
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
        parentStatusDomainService.recomputeParentOrderStatus(parentOrderSn);
    }

    private String truncate(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
