package com.aiolos.plaza.order.domain.order.status;

import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.ParentOrder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 父单状态领域服务：
 * 负责按子单集合重算父单展示态及多维状态，并以 CAS 方式落库
 */
@Service
public class ParentStatusDomainService {

    @Autowired
    private ParentOrderMapper parentOrderMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ParentOrderStatusCalculator parentOrderStatusCalculator;

    @Autowired
    private OrderStatusMetadataResolver orderStatusMetadataResolver;

    public void recomputeParentOrderStatus(String parentOrderSn) {
        if (!StringUtils.hasText(parentOrderSn)) {
            return;
        }
        ParentOrder parentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, parentOrderSn));
        if (parentOrder == null) {
            return;
        }
        // 父单本身不维护独立规则，始终以当前子单集合为准重新聚合，避免多入口各自改父单状态
        List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, parentOrderSn));
        if (childOrders == null || childOrders.isEmpty()) {
            return;
        }
        ParentOrderStatusCalculator.ParentOrderStatusSnapshot snapshot = parentOrderStatusCalculator.calculateSnapshot(childOrders);
        if (snapshot == null || targetStatusEquals(parentOrder, snapshot)) {
            return;
        }
        // 落库时带上旧 display status 做轻量 CAS，避免并发重算互相覆盖
        parentOrderMapper.update(null, orderStatusMetadataResolver.applyToParentUpdate(
                        new LambdaUpdateWrapper<ParentOrder>(),
                        snapshot
                )
                .set(ParentOrder::getUpdateTime, LocalDateTime.now())
                .eq(ParentOrder::getId, parentOrder.getId())
                .eq(ParentOrder::getStatus, parentOrder.getStatus()));
    }

    private boolean targetStatusEquals(ParentOrder parentOrder, ParentOrderStatusCalculator.ParentOrderStatusSnapshot snapshot) {
        // 只有展示态和三维状态全部一致时才跳过更新，避免部分字段已漂移却被忽略
        return parentOrder != null
                && snapshot != null
                && Objects.equals(parentOrder.getStatus(), snapshot.displayStatus())
                && Objects.equals(parentOrder.getPaymentStatus(), snapshot.paymentStatus())
                && Objects.equals(parentOrder.getFulfillmentStatus(), snapshot.fulfillmentStatus())
                && Objects.equals(parentOrder.getAftersaleStatus(), snapshot.aftersaleStatus());
    }
}
