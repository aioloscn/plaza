package com.aiolos.plaza.order.job;

import com.aiolos.plaza.order.coreflow.inventory.service.OrderInventoryService;
import com.aiolos.plaza.order.service.PlazaOrderService;
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

    /**
     * 订单超时自动取消任务（T+1兜底或定时扫描）
     * 建议每分钟或每5分钟执行一次
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
     * 当支付回调并发、消息重试或异常中断导致父子状态不一致时，按聚合规则进行自愈。
     */
    @XxlJob("parentOrderStatusReconcileJob")
    public void parentOrderStatusReconcileJob() {
        log.info("开始执行父子订单状态对账任务");
        long start = System.currentTimeMillis();
        try {
            plazaOrderService.reconcileParentOrderStatus(500);
            log.info("父子订单状态对账任务执行完成，耗时: {}ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("父子订单状态对账任务执行异常", e);
        }
    }
}
