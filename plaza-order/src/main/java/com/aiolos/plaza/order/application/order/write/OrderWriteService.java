package com.aiolos.plaza.order.application.order.write;

import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 订单聚合持久化服务
 * 负责把待提交的父单、子单与明细落库，并回填后续消息所需主键
 */
@Service
public class OrderWriteService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ParentOrderMapper parentOrderMapper;

    public OrderWriteService(OrderMapper orderMapper,
                                   OrderItemMapper orderItemMapper,
                                   ParentOrderMapper parentOrderMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.parentOrderMapper = parentOrderMapper;
    }

    public void persist(OrderCreateContext context) {
        Map<String, Long> orderIdBySn = new HashMap<>();
        for (Order order : context.getPendingOrders()) {
            orderMapper.insert(order);
            context.getOrderIds().add(order.getId());
            orderIdBySn.put(order.getOrderSn(), order.getId());
        }

        for (OrderItem item : context.getPendingOrderItems()) {
            Long orderId = orderIdBySn.get(item.getOrderSn());
            item.setOrderId(orderId);
            orderItemMapper.insert(item);
        }

        ParentOrder parentOrder = context.getPendingParentOrder();
        if (parentOrder != null) {
            parentOrderMapper.insert(parentOrder);
        }
    }
}
