package com.aiolos.plaza.order.application.payment.compensation;

import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.PaymentCompensationReasonCode;
import com.aiolos.plaza.enums.PaymentCompensationTaskStatus;
import com.aiolos.plaza.enums.PaymentCompensationType;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.mapper.PaymentCompensationTaskMapper;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.model.po.PaymentCompensationTask;
import com.aiolos.plaza.order.domain.payment.compensation.PaymentCompensationTaskFactory;
import com.aiolos.plaza.order.domain.order.status.OrderStateJudge;
import com.aiolos.plaza.order.application.payment.gateway.AlipayGatewaySupport;
import com.aiolos.plaza.order.application.payment.notify.PaymentNotifyRouter;
import com.aiolos.plaza.order.application.payment.notify.PaymentNotifyScenario;
import com.aiolos.plaza.order.application.payment.notify.model.PaymentResultPrecheck;
import com.aiolos.plaza.order.application.payment.notify.model.PaymentResultCommand;
import com.aiolos.plaza.order.application.payment.notify.model.PaymentResultContext;
import com.aiolos.plaza.order.application.payment.notify.PaymentNotifyOrchestrator;
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
public class PaymentCompensationTaskScheduler {

    private static final int BATCH_SIZE = 50;
    private static final long CLAIM_TIMEOUT_SECONDS = 300;
    private static final int[] RETRY_BACKOFF_MINUTES = {1, 5, 15, 30, 60, 120};

    private final PaymentCompensationTaskMapper paymentCompensationTaskMapper;
    private final ParentOrderMapper parentOrderMapper;
    private final PaymentCompensationTaskFactory paymentCompensationTaskFactory;
    private final PaymentNotifyOrchestrator paymentNotifySupport;
    private final PaymentNotifyRouter paymentNotifyRouter;
    private final OrderStateJudge orderStateJudge;
    private final AlipayGatewaySupport alipayGatewaySupport;
    private final RefundCompensationOrchestrator refundCompensationOrchestrator;

    /**
     * 构造补偿任务调度器，注入支付查询、退款补偿和任务持久化依赖。
     */
    public PaymentCompensationTaskScheduler(PaymentCompensationTaskMapper paymentCompensationTaskMapper,
                                            ParentOrderMapper parentOrderMapper,
                                            PaymentCompensationTaskFactory paymentCompensationTaskFactory,
                                            PaymentNotifyOrchestrator paymentNotifySupport,
                                            PaymentNotifyRouter paymentNotifyRouter,
                                            OrderStateJudge orderStateJudge,
                                            AlipayGatewaySupport alipayGatewaySupport,
                                            RefundCompensationOrchestrator refundCompensationOrchestrator) {
        this.paymentCompensationTaskMapper = paymentCompensationTaskMapper;
        this.parentOrderMapper = parentOrderMapper;
        this.paymentCompensationTaskFactory = paymentCompensationTaskFactory;
        this.paymentNotifySupport = paymentNotifySupport;
        this.paymentNotifyRouter = paymentNotifyRouter;
        this.orderStateJudge = orderStateJudge;
        this.alipayGatewaySupport = alipayGatewaySupport;
        this.refundCompensationOrchestrator = refundCompensationOrchestrator;
    }

    /**
     * 扫描并执行到期的补偿任务（待执行、待重试和超时 processing 任务）。
     */
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
            // 抢占失败说明被其它线程/实例先处理，当前轮次直接跳过，避免重复执行同一任务。
            if (claimTask(task, claimDeadline) == 0) {
                continue;
            }
            executeClaimedTask(task);
        }
    }

    /**
     * 扫描长时间未收敛的父单并补齐查单任务，同时补齐退款链路任务。
     */
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

        refundCompensationOrchestrator.enqueueRefundTasks(limit);
    }

    /**
     * 处理第三方退款回调并转交退款补偿编排器。
     */
    public String handleRefundNotify(Map<String, String> params) {
        return refundCompensationOrchestrator.handleRefundNotify(params);
    }

    /**
     * 按任务ID尝试抢占并立即执行，常用于手动触发或链路内即时驱动。
     */
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

    /**
     * 按退款请求号查找最新可执行退款任务并触发执行。
     */
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

    /**
     * 通过 CAS 抢占任务执行权，避免并发重复执行。
     */
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

    /**
     * 按补偿类型分发到具体执行逻辑，并统一处理异常重试。
     */
    private void executeClaimedTask(PaymentCompensationTask task) {
        try {
            PaymentCompensationType type = toTaskType(task.getCompensationType());
            // 补偿类型分流后，每条链路都能独立演进自己的重试和状态收敛策略。
            switch (type) {
                case PAYMENT_QUERY -> executePaymentQueryTask(task);
                case REFUND_EXECUTE -> refundCompensationOrchestrator.executeRefundExecuteTask(task);
                case REFUND_RECONCILE -> refundCompensationOrchestrator.executeRefundReconcileTask(task);
            }
        } catch (Exception e) {
            log.error("执行支付补偿任务异常，taskNo={}", task.getTaskNo(), e);
            retryOrManual(task, e.getMessage(), PaymentCompensationReasonCode.MANUAL_REQUIRED);
        }
    }

    /**
     * 执行支付查询补偿：查三方状态并驱动本地支付编排收敛。
     */
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
            PaymentResultCommand command = new PaymentResultCommand(
                    parentOrder.getParentOrderSn(),
                    "TRADE_SUCCESS",
                    queryResult.totalAmount() == null ? parentOrder.getPayAmount() : queryResult.totalAmount(),
                    queryResult.tradeNo(),
                    queryResult.buyerId()
            );
            PaymentResultPrecheck precheck = paymentNotifySupport.inspectChildOrders(parentOrder.getParentOrderSn());
            PaymentNotifyScenario scenario = paymentNotifyRouter.route(parentOrder, command, precheck, paymentNotifySupport);
            String result = scenario.handle(new PaymentResultContext(parentOrder, command, precheck), paymentNotifySupport);
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
            if (orderStateJudge.isPrePay(parentOrder) || orderStateJudge.isClosed(parentOrder)) {
                closeTask(task, "三方支付未成功，无需恢复");
                return;
            }
            // 本地已进入支付后状态但三方显示未支付/已关闭，自动补偿风险高，直接转人工。
            manualTask(task, "本地订单已进入支付后状态，但三方返回未支付/已关闭");
            return;
        }
        retryOrManual(task, queryResult.message(), PaymentCompensationReasonCode.PAYMENT_CALLBACK_TIMEOUT);
    }

    /**
     * 失败后按退避策略重试；超过阈值转人工介入。
     */
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

    /**
     * 把任务标记为人工处理待办，停止自动补偿。
     */
    private void manualTask(PaymentCompensationTask task, String failReason) {
        // 支付查单补偿转人工时，只收敛任务自身状态，后续由人工介入排查支付状态冲突。
        paymentCompensationTaskMapper.update(null, new LambdaUpdateWrapper<PaymentCompensationTask>()
                .set(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.MANUAL_PENDING.getCode())
                .set(PaymentCompensationTask::getReasonCode, PaymentCompensationReasonCode.MANUAL_REQUIRED.getCode())
                .set(PaymentCompensationTask::getFailReason, truncate(failReason))
                .set(PaymentCompensationTask::getUpdateTime, LocalDateTime.now())
                .eq(PaymentCompensationTask::getId, task.getId())
                .eq(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.PROCESSING.getCode()));
    }

    /**
     * 任务执行成功后标记完成并记录三方状态。
     */
    private void markTaskSuccess(PaymentCompensationTask task, String thirdPartyStatus, String message) {
        paymentCompensationTaskMapper.update(null, new LambdaUpdateWrapper<PaymentCompensationTask>()
                .set(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.SUCCESS.getCode())
                .set(PaymentCompensationTask::getThirdPartyStatus, thirdPartyStatus)
                .set(PaymentCompensationTask::getFailReason, truncate(message))
                .set(PaymentCompensationTask::getUpdateTime, LocalDateTime.now())
                .eq(PaymentCompensationTask::getId, task.getId())
                .eq(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.PROCESSING.getCode()));
    }

    /**
     * 关闭无需继续补偿的任务。
     */
    private void closeTask(PaymentCompensationTask task, String message) {
        paymentCompensationTaskMapper.update(null, new LambdaUpdateWrapper<PaymentCompensationTask>()
                .set(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.CLOSED.getCode())
                .set(PaymentCompensationTask::getFailReason, truncate(message))
                .set(PaymentCompensationTask::getUpdateTime, LocalDateTime.now())
                .eq(PaymentCompensationTask::getId, task.getId())
                .eq(PaymentCompensationTask::getStatus, PaymentCompensationTaskStatus.PROCESSING.getCode()));
    }

    /**
     * 以 businessKey 幂等插入或更新补偿任务。
     */
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
        PaymentCompensationTask task = paymentCompensationTaskFactory.buildTask(
                type, businessKey, parentOrderSn, orderSn, tradeNo, refundRequestNo, reasonCode, nextRetryTime);
        paymentCompensationTaskMapper.insert(task);
    }

    /**
     * 按父订单号加载父单快照。
     */
    private ParentOrder loadParentOrder(String parentOrderSn) {
        if (!StringUtils.hasText(parentOrderSn)) {
            return null;
        }
        return parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, parentOrderSn)
                .last("LIMIT 1"));
    }

    /**
     * 把任务类型编码转换为枚举定义。
     */
    private PaymentCompensationType toTaskType(Integer code) {
        for (PaymentCompensationType value : PaymentCompensationType.values()) {
            if (value.getCode().equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知补偿任务类型: " + code);
    }

    /**
     * 根据重试次数计算退避间隔（分钟）。
     */
    private long resolveBackoffMinutes(int retryCount) {
        // 退避时间统一集中管理，避免不同补偿链路各自定义一套重试节奏。
        int index = Math.max(0, Math.min(retryCount - 1, RETRY_BACKOFF_MINUTES.length - 1));
        return RETRY_BACKOFF_MINUTES[index];
    }

    /**
     * 生成支付查询任务的业务幂等键。
     */
    private String buildPaymentQueryBusinessKey(String parentOrderSn) {
        return "payment-query:" + parentOrderSn;
    }

    /**
     * 截断长错误信息，避免超过数据库字段长度。
     */
    private String truncate(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

}
