package com.aiolos.plaza.order.application.payment.compensation;

import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.OrderType;
import com.aiolos.plaza.enums.PaymentCompensationReasonCode;
import com.aiolos.plaza.enums.PaymentCompensationTaskStatus;
import com.aiolos.plaza.enums.PaymentCompensationType;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.RefundOrderStatus;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.mapper.PaymentCompensationTaskMapper;
import com.aiolos.plaza.mapper.RefundLogMapper;
import com.aiolos.plaza.mapper.RefundOrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final OrderItemMapper orderItemMapper;
    private final PaymentCompensationTaskFactory paymentCompensationTaskFactory;
    private final RefundOrderFactory refundOrderFactory;
    private final RefundLogFactory refundLogFactory;
    private final OrderStateMachineService orderStateMachineService;
    private final ParentOrderRefreshAppService parentOrderRefreshAppService;
    private final OrderStateJudge orderStateJudge;
    private final OrderStatusMetadataResolver orderStatusMetadataResolver;
    private final AlipayGatewaySupport alipayGatewaySupport;
    private final StockReservationService stockReservationService;
    private final StringRedisTemplate shopRedisTemplate;

    /**
     * 构造退款补偿编排器，注入退款任务编排与状态收敛所需依赖。
     */
    public RefundCompensationOrchestrator(PaymentCompensationTaskMapper paymentCompensationTaskMapper,
                                      RefundOrderMapper refundOrderMapper,
                                      RefundLogMapper refundLogMapper,
                                      ParentOrderMapper parentOrderMapper,
                                      OrderMapper orderMapper,
                                      OrderItemMapper orderItemMapper,
                                      PaymentCompensationTaskFactory paymentCompensationTaskFactory,
                                      RefundOrderFactory refundOrderFactory,
                                      RefundLogFactory refundLogFactory,
                                      OrderStateMachineService orderStateMachineService,
                                      ParentOrderRefreshAppService parentOrderRefreshAppService,
                                      OrderStateJudge orderStateJudge,
                                      OrderStatusMetadataResolver orderStatusMetadataResolver,
                                      AlipayGatewaySupport alipayGatewaySupport,
                                      StockReservationService stockReservationService,
                                      @Qualifier("shopRedisTemplate") StringRedisTemplate shopRedisTemplate) {
        this.paymentCompensationTaskMapper = paymentCompensationTaskMapper;
        this.refundOrderMapper = refundOrderMapper;
        this.refundLogMapper = refundLogMapper;
        this.parentOrderMapper = parentOrderMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.paymentCompensationTaskFactory = paymentCompensationTaskFactory;
        this.refundOrderFactory = refundOrderFactory;
        this.refundLogFactory = refundLogFactory;
        this.orderStateMachineService = orderStateMachineService;
        this.parentOrderRefreshAppService = parentOrderRefreshAppService;
        this.orderStateJudge = orderStateJudge;
        this.orderStatusMetadataResolver = orderStatusMetadataResolver;
        this.alipayGatewaySupport = alipayGatewaySupport;
        this.stockReservationService = stockReservationService;
        this.shopRedisTemplate = shopRedisTemplate;
    }

    /**
     * 扫描待处理退款单并补齐退款执行/退款对账任务。
     */
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

    /**
     * 处理退款回调：验签、记日志并补齐退款对账任务。
     */
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

    /**
     * 执行退款任务：发起三方退款并根据结果更新任务与退款单状态。
     */
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

    /**
     * 执行退款对账任务：查询三方退款状态并做最终收敛。
     */
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

    /**
     * 确保退款单存在；不存在时按父单信息补建退款单。
     */
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

    /**
     * 退款成功后的最终收敛：更新退款单、回滚库存并推进父子单状态。
     */
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
            clearSeckillBoughtUserIfNeeded(child);
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

    /**
     * 若为秒杀订单，移除已购集合标记，允许用户在退款后再次参与。
     */
    private void clearSeckillBoughtUserIfNeeded(Order order) {
        if (order == null) {
            return;
        }
        if (!OrderType.SECKILL.getCode().equals(order.getOrderType())) {
            return;
        }
        if (order.getActivityId() == null || order.getUserId() == null) {
            return;
        }
        String boughtKey = RedisKeyEnum.SECKILL_BOUGHT_USERS.getKey(order.getActivityId());
        Long removed = shopRedisTemplate.opsForSet().remove(boughtKey, String.valueOf(order.getUserId()));
        if (removed == null || removed <= 0) {
            return;
        }
        List<OrderItem> orderItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId()));
        int totalQuantity = 0;
        for (OrderItem item : orderItems) {
            if (item == null || item.getProductQuantity() == null || item.getProductQuantity() <= 0) {
                continue;
            }
            totalQuantity += item.getProductQuantity();
        }
        if (totalQuantity > 0) {
            String stockKey = RedisKeyEnum.SECKILL_STOCK.getKey(order.getActivityId());
            shopRedisTemplate.opsForValue().increment(stockKey, totalQuantity);
        }
    }

    /**
     * 标记退款单为处理中，并设置下次对账时间。
     */
    private void markRefundProcessing(RefundOrder refundOrder, String message) {
        refundOrderMapper.update(null, new LambdaUpdateWrapper<RefundOrder>()
                .set(RefundOrder::getStatus, RefundOrderStatus.PROCESSING.getCode())
                .set(RefundOrder::getFailReason, truncate(message))
                .set(RefundOrder::getNextRetryTime, LocalDateTime.now().plusMinutes(1))
                .set(RefundOrder::getUpdateTime, LocalDateTime.now())
                .eq(RefundOrder::getRefundRequestNo, refundOrder.getRefundRequestNo()));
    }

    /**
     * 标记退款单失败并写入退避后的下次重试时间。
     */
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

    /**
     * 任务失败后按次数重试；超阈值时转人工处理。
     */
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

    /**
     * 把补偿任务与退款单升级为人工介入，并同步子单到退款失败状态。
     */
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

    /**
     * 标记补偿任务执行成功并记录三方状态。
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
     * 基于业务键幂等插入/更新退款补偿任务。
     */
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
     * 按退款请求号加载退款单。
     */
    private RefundOrder loadRefundOrder(String refundRequestNo) {
        if (!StringUtils.hasText(refundRequestNo)) {
            return null;
        }
        return refundOrderMapper.selectOne(new LambdaQueryWrapper<RefundOrder>()
                .eq(RefundOrder::getRefundRequestNo, refundRequestNo)
                .last("LIMIT 1"));
    }

    /**
     * 根据重试次数计算指数退避间隔（分钟）。
     */
    private long resolveBackoffMinutes(int retryCount) {
        int index = Math.max(0, Math.min(retryCount - 1, RETRY_BACKOFF_MINUTES.length - 1));
        return RETRY_BACKOFF_MINUTES[index];
    }

    /**
     * 生成退款补偿任务的业务幂等键。
     */
    private String buildRefundBusinessKey(PaymentCompensationType type, String refundRequestNo) {
        return type.name().toLowerCase() + ":" + refundRequestNo;
    }

    /**
     * 当退款补偿失败需人工介入时，统一把关联子单推进到退款失败。
     */
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

    /**
     * 截断错误信息，防止写库超长。
     */
    private String truncate(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
