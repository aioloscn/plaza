package com.aiolos.plaza.order.job;

import com.aiolos.plaza.order.application.payment.PaymentCompensationTaskService;
import com.aiolos.plaza.order.coreflow.inventory.service.OrderInventoryService;
import com.aiolos.plaza.order.api.PlazaOrderService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 订单相关定时任务
 */
@Slf4j
@Component
public class OrderJob {

    @Autowired
    private PlazaOrderService plazaOrderService;

    @Autowired
    private OrderInventoryService orderInventoryService;

    @Autowired
    private PaymentCompensationTaskService paymentCompensationTaskService;

    /**
     * 订单超时自动取消任务（T+1兜底或定时扫描）
     * 0 0/5 * * * ?
     */
    @XxlJob("orderTimeoutCancelJob")
    public void orderTimeoutCancelJob() {
        log.info("开始执行订单超时自动取消兜底任务");
        long start = System.currentTimeMillis();
        try {
            plazaOrderService.cancelTimeoutOrders();
            orderInventoryService.expireReservations(200);
            log.info("订单超时自动取消兜底任务执行完成，耗时: {}ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("订单超时自动取消兜底任务执行异常", e);
        }
    }

    /**
     * 父子订单状态对账任务：
     * 当支付回调并发、消息重试或异常中断导致父子状态不一致时，按聚合规则进行自愈
     * 0 0/1 * * * ?
     */
    @XxlJob("parentOrderStatusReconcileJob")
    public void parentOrderStatusReconcileJob() {
        long start = System.currentTimeMillis();
        log.info("开始执行父子订单状态对账任务");
        try {
            plazaOrderService.reconcileParentOrderStatus(500);
            paymentCompensationTaskService.enqueueReconcileTasks(200);
            log.info("父子订单状态对账任务执行完成，耗时: {}ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("父子订单状态对账任务执行异常", e);
        }
    }

    /**
     * 支付补偿任务扫描执行：
     * 支付查询兜底、退款执行、退款对账统一由这里驱动
     * 0/15 * * * * ?
     */
    @XxlJob("paymentCompensationTaskJob")
    public void paymentCompensationTaskJob() {
        long start = System.currentTimeMillis();
        log.info("开始执行支付补偿任务");
        try {
            paymentCompensationTaskService.processReadyTasks();
            log.info("支付补偿任务执行完成，耗时: {}ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("支付补偿任务执行异常", e);
        }
    }
}
