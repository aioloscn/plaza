package com.aiolos.plaza.order.facade;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.model.bo.OrderSubmitReq;
import com.aiolos.plaza.order.domain.status.ParentStatusDomainService;
import com.aiolos.plaza.order.application.order.TimeoutCloseService;
import com.aiolos.plaza.order.application.order.SubmitAppService;
import com.aiolos.plaza.order.application.confirm.ConfirmService;
import com.aiolos.plaza.order.api.PlazaOrderService;
import com.aiolos.plaza.order.domain.status.OrderStatusMetadataResolver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import com.aiolos.plaza.mapper.ShopMapper;
import com.aiolos.plaza.model.po.Shop;
import com.aiolos.plaza.order.model.vo.OrderConfirmVO;
import com.aiolos.plaza.order.model.vo.OrderItemVO;
import com.aiolos.plaza.order.model.vo.OrderListVO;

@Slf4j
@Service
public class OrderFacade implements PlazaOrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private ParentOrderMapper parentOrderMapper;

    @Autowired
    private ShopMapper shopMapper;
    
    @Autowired
    private OrderStatusMetadataResolver orderStatusMetadataResolver;

    @Autowired
    private ConfirmService confirmService;

    @Autowired
    private ParentStatusDomainService parentStatusDomainService;

    @Autowired
    private SubmitAppService submitAppService;

    @Autowired
    private TimeoutCloseService timeoutCloseService;

    /**
     * 下单前确认
     * 调用时机：结算页点击“提交订单”前，用于给前端返回差异并签发 `confirmToken`
     */
    @Override
    public OrderConfirmVO confirm(Long userId, OrderSubmitReq req) {
        return confirmService.confirm(userId, req);
    }
    
    /**
     * 下单入口
     * 调用时机：前端调用 `/order/confirm` 并拿到 `confirmToken` 后，再调用本方法
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String submit(Long userId, OrderSubmitReq req) {
        return submitAppService.submit(userId, req);
    }

    @Override
    public OrderListVO getPayInfo(Long userId, String paySn) {
        // 先查询父订单
        LambdaQueryWrapper<ParentOrder> parentQuery = new LambdaQueryWrapper<>();
        parentQuery.eq(ParentOrder::getUserId, userId);
        parentQuery.eq(ParentOrder::getParentOrderSn, paySn);
        ParentOrder parentOrder = parentOrderMapper.selectOne(parentQuery);
        
        if (parentOrder == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }

        // 再查询子订单，主要是为了获取收货人等信息
        LambdaQueryWrapper<Order> query = new LambdaQueryWrapper<>();
        query.eq(Order::getUserId, userId);
        query.eq(Order::getParentOrderSn, paySn);
        List<Order> orders = orderMapper.selectList(query);
        
        if (orders == null || orders.isEmpty()) {
             ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        
        Order mainOrder = orders.get(0);
        
        OrderListVO vo = new OrderListVO();
        BeanUtils.copyProperties(mainOrder, vo);
        // 使用父订单金额和编号
        vo.setParentOrderSn(parentOrder.getParentOrderSn());
        vo.setPayAmount(parentOrder.getPayAmount());
        vo.setTotalAmount(parentOrder.getTotalAmount());
        vo.setStatus(parentOrder.getStatus());
        vo.setPaymentStatus(parentOrder.getPaymentStatus());
        vo.setFulfillmentStatus(parentOrder.getFulfillmentStatus());
        vo.setAftersaleStatus(parentOrder.getAftersaleStatus());
        enrichStatusView(vo);
        
        return vo;
    }

    @Override
    public OrderListVO getDetail(Long userId, Long orderId) {
        // 1. 获取订单
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        
        // 校验订单归属
        if (!order.getUserId().equals(userId)) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NO_PERMISSION);
        }
        
        // 2. 获取订单项
        LambdaQueryWrapper<OrderItem> itemQuery = new LambdaQueryWrapper<>();
        itemQuery.eq(OrderItem::getOrderId, orderId);
        List<OrderItem> orderItems = orderItemMapper.selectList(itemQuery);
        
        // 3. 获取店铺信息
        Shop shop = shopMapper.selectById(order.getShopId());
        
        // 4. 组装数据
        OrderListVO vo = new OrderListVO();
        BeanUtils.copyProperties(order, vo);
        if (shop != null) {
            vo.setShopName(shop.getName());
        }
        
        // 填充状态描述并计算剩余时间
        enrichStatusView(vo);
        
        // 计算剩余支付时间，10 分钟 = 600000 毫秒
        if ((OrderState.RESERVING.getCode().equals(order.getStatus())
                || OrderState.CREATED.getCode().equals(order.getStatus())
                || OrderState.PAYING.getCode().equals(order.getStatus())
                || OrderState.CLOSING.getCode().equals(order.getStatus()))
                && order.getCreateTime() != null) {
            LocalDateTime expireTime = order.getCreateTime().plusMinutes(10);
            // 计算从现在到过期时间的毫秒数
            long remainMillis = java.time.Duration.between(LocalDateTime.now(), expireTime).toMillis();
            vo.setRemainTime(Math.max(remainMillis, 0L));
        } else {
            vo.setRemainTime(0L);
        }
        
        List<OrderItemVO> itemVOs = orderItems.stream().map(item -> {
            OrderItemVO itemVO = new OrderItemVO();
            BeanUtils.copyProperties(item, itemVO);
            return itemVO;
        }).collect(Collectors.toList());
        vo.setItems(itemVOs);
        
        return vo;
    }

    @Override
    public List<OrderListVO> list(Long userId, Integer status) {
        // 1. 查询父订单列表
        LambdaQueryWrapper<ParentOrder> parentQuery = new LambdaQueryWrapper<>();
        parentQuery.eq(ParentOrder::getUserId, userId);
        if (status != null) {
            // 前端“待付款”标签值为 0，这里兼容把“支付中”也纳入同一标签查询结果
            if (OrderState.CREATED.getCode().equals(status)) {
                parentQuery.in(ParentOrder::getStatus, Arrays.asList(OrderState.RESERVING.getCode(), OrderState.CREATED.getCode(), OrderState.PAYING.getCode()));
            } else {
                parentQuery.eq(ParentOrder::getStatus, status);
            }
        }
        parentQuery.orderByDesc(ParentOrder::getCreateTime);
        List<ParentOrder> parentOrders = parentOrderMapper.selectList(parentQuery);

        if (parentOrders == null || parentOrders.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 批量查询子订单
        List<String> parentOrderSns = parentOrders.stream().map(ParentOrder::getParentOrderSn).collect(Collectors.toList());
        LambdaQueryWrapper<Order> orderQuery = new LambdaQueryWrapper<>();
        orderQuery.in(Order::getParentOrderSn, parentOrderSns);
        List<Order> orders = orderMapper.selectList(orderQuery);

        if (orders == null || orders.isEmpty()) {
            return new ArrayList<>();
        }

        // 按 `parentOrderSn` 进行分组
        Map<String, List<Order>> parentOrderMap = orders.stream()
                .collect(Collectors.groupingBy(Order::getParentOrderSn, 
                        Collectors.toList()));

        // 3. 批量查询订单项
        List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
        LambdaQueryWrapper<OrderItem> itemQuery = new LambdaQueryWrapper<>();
        itemQuery.in(OrderItem::getOrderId, orderIds);
        List<OrderItem> orderItems = orderItemMapper.selectList(itemQuery);
        
        // 4. 批量查询店铺信息
        List<Long> shopIds = orders.stream().map(Order::getShopId).distinct().collect(Collectors.toList());
        List<Shop> shops = new ArrayList<>();
        if (!shopIds.isEmpty()) {
            shops = shopMapper.selectBatchIds(shopIds);
        }
        Map<Long, String> shopMap = shops.stream().collect(Collectors.toMap(Shop::getId, Shop::getName));

        // 5. 组装数据
        Map<Long, List<OrderItem>> orderItemMap = orderItems.stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));

        List<OrderListVO> result = new ArrayList<>();

        // 按父订单维度组装返回值
        for (ParentOrder parentOrder : parentOrders) {
            String parentOrderSn = parentOrder.getParentOrderSn();
            List<Order> childOrders = parentOrderMap.getOrDefault(parentOrderSn, new ArrayList<>());
            
            if (childOrders.isEmpty()) {
                continue;
            }
            
            // 按创建时间倒序，取第一条作为基础信息，例如收货地址等
            childOrders.sort((o1, o2) -> o2.getCreateTime().compareTo(o1.getCreateTime()));
            Order mainOrder = childOrders.get(0);

            OrderListVO vo = new OrderListVO();
            BeanUtils.copyProperties(mainOrder, vo);
            
            // 覆盖部分关键字段为聚合后的值
            vo.setOrderSn(parentOrderSn); // 前端使用 orderSn 做展示和支付，这里直接替换成 parentOrderSn
            vo.setId(parentOrder.getId()); // 使用父订单ID
            vo.setTotalAmount(parentOrder.getTotalAmount());
            vo.setPayAmount(parentOrder.getPayAmount());
            vo.setStatus(parentOrder.getStatus());
            vo.setPaymentStatus(parentOrder.getPaymentStatus());
            vo.setFulfillmentStatus(parentOrder.getFulfillmentStatus());
            vo.setAftersaleStatus(parentOrder.getAftersaleStatus());
            vo.setCreateTime(parentOrder.getCreateTime());
            
            enrichStatusView(vo);

            // 如果只有一个子订单，就显示真实店铺名；如果有多个，就显示“多店合并订单”
            if (childOrders.size() == 1) {
                vo.setShopName(shopMap.getOrDefault(mainOrder.getShopId(), "未知店铺"));
            } else {
                vo.setShopName("多店合并订单");
            }

            List<OrderItemVO> allItemVOs = new ArrayList<>();

            for (Order childOrder : childOrders) {
                List<OrderItem> items = orderItemMap.get(childOrder.getId());
                if (items != null) {
                    List<OrderItemVO> itemVOs = items.stream().map(item -> {
                        OrderItemVO itemVO = new OrderItemVO();
                        BeanUtils.copyProperties(item, itemVO);
                        return itemVO;
                    }).collect(Collectors.toList());
                    allItemVOs.addAll(itemVOs);
                }
            }

            vo.setItems(allItemVOs);

            // 计算剩余支付时间，10 分钟
            if ((OrderState.RESERVING.getCode().equals(parentOrder.getStatus())
                    || OrderState.CREATED.getCode().equals(parentOrder.getStatus())
                    || OrderState.PAYING.getCode().equals(parentOrder.getStatus())
                    || OrderState.CLOSING.getCode().equals(parentOrder.getStatus()))
                    && parentOrder.getCreateTime() != null) {
                LocalDateTime expireTime = parentOrder.getCreateTime().plusMinutes(10);
                long remainMillis = java.time.Duration.between(LocalDateTime.now(), expireTime).toMillis();
                vo.setRemainTime(Math.max(remainMillis, 0L));
            } else {
                vo.setRemainTime(0L);
            }

            result.add(vo);
        }

        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelTimeoutOrders() {
        timeoutCloseService.cancelTimeoutOrders();
    }

    /**
     * 父单状态兜底对账任务入口
     * 用于修复极端并发或异常中断导致的父子状态短暂不一致
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reconcileParentOrderStatus(int batchSize) {
        int finalBatchSize = batchSize > 0 ? batchSize : 200;
        List<ParentOrder> parentOrders = parentOrderMapper.selectList(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getDeleteStatus, 0)
                .orderByAsc(ParentOrder::getId)
                .last("limit " + finalBatchSize));
        if (parentOrders == null || parentOrders.isEmpty()) {
            return;
        }
        for (ParentOrder parentOrder : parentOrders) {
            parentStatusDomainService.recomputeParentOrderStatus(parentOrder.getParentOrderSn());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        timeoutCloseService.cancelOrder(orderId);
    }
    
    private void enrichStatusView(OrderListVO vo) {
        vo.setStatusDesc(orderStatusMetadataResolver.getDisplayStatusDesc(vo.getStatus()));
        vo.setPaymentStatusDesc(orderStatusMetadataResolver.getPaymentStatusDesc(vo.getPaymentStatus()));
        vo.setFulfillmentStatusDesc(orderStatusMetadataResolver.getFulfillmentStatusDesc(vo.getFulfillmentStatus()));
        vo.setAftersaleStatusDesc(orderStatusMetadataResolver.getAftersaleStatusDesc(vo.getAftersaleStatus()));
    }
}
