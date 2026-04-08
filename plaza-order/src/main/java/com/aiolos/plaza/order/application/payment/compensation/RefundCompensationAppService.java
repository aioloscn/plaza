package com.aiolos.plaza.order.application.payment.compensation;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.PaymentCompensationReasonCode;
import com.aiolos.plaza.enums.PaymentCompensationTaskStatus;
import com.aiolos.plaza.enums.PaymentCompensationType;
import com.aiolos.plaza.enums.RefundOrderStatus;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.mapper.PaymentCompensationTaskMapper;
import com.aiolos.plaza.mapper.RefundOrderMapper;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.model.po.PaymentCompensationTask;
import com.aiolos.plaza.model.po.RefundOrder;
import com.aiolos.plaza.order.domain.payment.compensation.PaymentCompensationTaskFactory;
import com.aiolos.plaza.order.domain.payment.compensation.RefundOrderFactory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 退款补偿命令服务：负责补建退款单、补偿任务，并按需触发执行
 */
@Service
public class RefundCompensationAppService {

    private final ParentOrderMapper parentOrderMapper;
    private final RefundOrderMapper refundOrderMapper;
    private final PaymentCompensationTaskMapper paymentCompensationTaskMapper;
    private final PaymentCompensationTaskFactory paymentCompensationTaskFactory;
    private final RefundOrderFactory refundOrderFactory;

    public RefundCompensationAppService(ParentOrderMapper parentOrderMapper,
                                            RefundOrderMapper refundOrderMapper,
                                            PaymentCompensationTaskMapper paymentCompensationTaskMapper,
                                            PaymentCompensationTaskFactory paymentCompensationTaskFactory,
                                            RefundOrderFactory refundOrderFactory) {
        this.parentOrderMapper = parentOrderMapper;
        this.refundOrderMapper = refundOrderMapper;
        this.paymentCompensationTaskMapper = paymentCompensationTaskMapper;
        this.paymentCompensationTaskFactory = paymentCompensationTaskFactory;
        this.refundOrderFactory = refundOrderFactory;
    }

    /**
     * 提交退款补偿请求：补建退款单并确保存在可执行的退款补偿任务
     */
    public String submitRefundCompensation(String parentOrderSn,
                                           String tradeNo,
                                           PaymentCompensationReasonCode reasonCode) {
        // 统一通过补偿任务驱动退款执行，返回退款请求号给上层链路追踪
        return prepareRefundCompensationTask(parentOrderSn, tradeNo, reasonCode).getRefundRequestNo();
    }

    private PaymentCompensationTask prepareRefundCompensationTask(String parentOrderSn,
                                                                  String tradeNo,
                                                                  PaymentCompensationReasonCode reasonCode) {
        // 只有父单处于 REFUNDING 才允许创建/补建退款补偿任务，防止越状态执行
        ParentOrder parentOrder = requireRefundingParentOrder(parentOrderSn);
        String refundRequestNo = refundOrderFactory.buildRefundRequestNo(parentOrderSn);
        String effectiveTradeNo = StringUtils.hasText(tradeNo) ? tradeNo : parentOrder.getTradeNo();

        // 退款单幂等：已有则复用，不存在才新建
        RefundOrder refundOrder = loadRefundOrder(refundRequestNo);
        if (refundOrder == null) {
            refundOrder = refundOrderFactory.buildRefundOrder(parentOrder, refundRequestNo, reasonCode);
            refundOrder.setTradeNo(effectiveTradeNo);
            refundOrderMapper.insert(refundOrder);
        } else if (StringUtils.hasText(effectiveTradeNo) && !StringUtils.hasText(refundOrder.getTradeNo())) {
            // 历史记录可能缺 tradeNo，这里补齐，便于后续网关查询与对账
            refundOrderMapper.update(null, new LambdaUpdateWrapper<RefundOrder>()
                    .set(RefundOrder::getTradeNo, effectiveTradeNo)
                    .set(RefundOrder::getUpdateTime, LocalDateTime.now())
                    .eq(RefundOrder::getId, refundOrder.getId()));
            refundOrder.setTradeNo(effectiveTradeNo);
        }

        PaymentCompensationType taskType = RefundOrderStatus.PROCESSING.getCode().equals(refundOrder.getStatus())
                ? PaymentCompensationType.REFUND_RECONCILE
                : PaymentCompensationType.REFUND_EXECUTE;
        // 处理中退款单优先补建对账任务，其余状态默认补建执行任务
        return ensureRefundTask(taskType, parentOrderSn, effectiveTradeNo, refundOrder, reasonCode);
    }

    private PaymentCompensationTask ensureRefundTask(PaymentCompensationType type,
                                                     String parentOrderSn,
                                                     String tradeNo,
                                                     RefundOrder refundOrder,
                                                     PaymentCompensationReasonCode reasonCode) {
        String businessKey = buildRefundBusinessKey(type, refundOrder.getRefundRequestNo());
        PaymentCompensationTask existing = paymentCompensationTaskMapper.selectOne(
                new LambdaQueryWrapper<PaymentCompensationTask>()
                        .eq(PaymentCompensationTask::getBusinessKey, businessKey)
                        .last("LIMIT 1")
        );
        if (existing != null) {
            // 已终态任务直接复用，保证幂等且避免重复触发
            if (PaymentCompensationTaskStatus.SUCCESS.getCode().equals(existing.getStatus())
                    || PaymentCompensationTaskStatus.CLOSED.getCode().equals(existing.getStatus())) {
                return existing;
            }
            // 未终态任务刷新关键字段并立即可重试
            LocalDateTime now = LocalDateTime.now();
            paymentCompensationTaskMapper.update(null, new LambdaUpdateWrapper<PaymentCompensationTask>()
                    .set(PaymentCompensationTask::getTradeNo, tradeNo)
                    .set(PaymentCompensationTask::getReasonCode, reasonCode == null ? null : reasonCode.getCode())
                    .set(PaymentCompensationTask::getNextRetryTime, now)
                    .set(PaymentCompensationTask::getUpdateTime, now)
                    .eq(PaymentCompensationTask::getId, existing.getId()));
            existing.setTradeNo(tradeNo);
            existing.setReasonCode(reasonCode == null ? null : reasonCode.getCode());
            existing.setNextRetryTime(now);
            return existing;
        }
        // 首次创建补偿任务，后续由任务执行器统一消费
        PaymentCompensationTask task = paymentCompensationTaskFactory.buildTask(
                type,
                businessKey,
                parentOrderSn,
                null,
                tradeNo,
                refundOrder.getRefundRequestNo(),
                reasonCode,
                LocalDateTime.now()
        );
        paymentCompensationTaskMapper.insert(task);
        return task;
    }

    private ParentOrder requireRefundingParentOrder(String parentOrderSn) {
        ParentOrder parentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, parentOrderSn)
                .last("LIMIT 1"));
        if (parentOrder == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        if (!OrderState.REFUNDING.getCode().equals(parentOrder.getStatus())) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }
        return parentOrder;
    }

    private RefundOrder loadRefundOrder(String refundRequestNo) {
        if (!StringUtils.hasText(refundRequestNo)) {
            return null;
        }
        return refundOrderMapper.selectOne(new LambdaQueryWrapper<RefundOrder>()
                .eq(RefundOrder::getRefundRequestNo, refundRequestNo)
                .last("LIMIT 1"));
    }

    private String buildRefundBusinessKey(PaymentCompensationType type, String refundRequestNo) {
        return type.name().toLowerCase() + ":" + refundRequestNo;
    }
}
