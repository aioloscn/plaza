package com.aiolos.plaza.order.service.impl;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.AddressMapper;
import com.aiolos.plaza.mapper.CartItemMapper;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.mapper.ProductMapper;
import com.aiolos.plaza.model.po.Address;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.model.po.Product;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.config.OrderStateChangeInterceptor;
import com.aiolos.plaza.order.chain.ChainExecutor;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.order.chain.handler.order.AddressCheckHandler;
import com.aiolos.plaza.order.chain.handler.order.CartClearHandler;
import com.aiolos.plaza.order.chain.handler.order.CartFetchHandler;
import com.aiolos.plaza.order.chain.handler.order.DelayMessageSendHandler;
import com.aiolos.plaza.order.chain.handler.order.LocalMessageSaveHandler;
import com.aiolos.plaza.order.chain.handler.order.OrderBuildHandler;
import com.aiolos.plaza.order.coreflow.inventory.service.OrderInventoryService;
import com.aiolos.plaza.order.model.bo.OrderSubmitReq;
import com.aiolos.plaza.order.service.PlazaOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import com.aiolos.plaza.mapper.ShopMapper;
import com.aiolos.plaza.model.po.Shop;
import com.aiolos.plaza.order.model.vo.OrderConfirmVO;
import com.aiolos.plaza.order.model.vo.OrderItemVO;
import com.aiolos.plaza.order.model.vo.OrderListVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import com.aiolos.plaza.enums.OrderEvent;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@Service
public class PlazaOrderServiceImpl implements PlazaOrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private ParentOrderMapper parentOrderMapper;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private CartItemMapper cartItemMapper;

    @Autowired
    private AddressMapper addressMapper;

    @Autowired
    private OrderInventoryService orderInventoryService;

    @Autowired
    private StateMachineFactory<OrderState, OrderEvent> orderStateMachineFactory;

    @Autowired
    private OrderStateChangeInterceptor orderStateChangeInterceptor;
    
    @Autowired
    private ChainExecutor chainExecutor;

    @Autowired
    private AddressCheckHandler addressCheckHandler;

    @Autowired
    private CartFetchHandler cartFetchHandler;

    @Autowired
    private OrderBuildHandler orderBuildHandler;

    @Autowired
    private CartClearHandler cartClearHandler;

    @Autowired
    private LocalMessageSaveHandler localMessageSaveHandler;

    @Autowired
    private DelayMessageSendHandler delayMessageSendHandler;

    @Autowired
    @Qualifier("orderRedisTemplate")
    private StringRedisTemplate orderRedisTemplate;

    /**
     * 下单前确认（确认阶段）
     * 调用时机：结算页点击“提交订单”前，用于给前端返回差异并签发 confirmToken。
     */
    @Override
    public OrderConfirmVO confirm(Long userId, OrderSubmitReq req) {
        PrecheckSnapshot snapshot = buildPrecheckSnapshot(userId, req);
        OrderConfirmVO vo = new OrderConfirmVO();
        vo.setTotalAmount(snapshot.totalAmount);
        vo.setItemCount(snapshot.items.size());
        vo.setItems(snapshot.items);
        vo.setReady(snapshot.ready);
        if (snapshot.ready) {
            String token = UUID.randomUUID().toString().replace("-", "");
            String key = RedisKeyEnum.ORDER_CONFIRM_TOKEN.getKey(userId, token);
            // token 只保存快照指纹，不保存明细，提交时重算指纹对比
            orderRedisTemplate.opsForValue().set(key, snapshot.fingerprint, RedisKeyEnum.ORDER_CONFIRM_TOKEN.getDefaultExpireSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            vo.setConfirmToken(token);
        }
        return vo;
    }
    
    /**
     * 下单入口（提交阶段）
     * 调用时机：前端调用 /order/confirm 且拿到 confirmToken 后，再调用本方法。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String submit(Long userId, OrderSubmitReq req) {
        // 先校验确认令牌，确保提交时商品/价格/库存与确认阶段一致
        validateConfirmToken(userId, req);

        OrderCreateContext context = new OrderCreateContext();
        context.setUserId(userId);
        context.setReq(req);

        List<ChainHandler<OrderCreateContext>> handlers = Arrays.asList(
                addressCheckHandler,
                cartFetchHandler,
                orderBuildHandler,
                cartClearHandler,
                localMessageSaveHandler,
                delayMessageSendHandler
        );

        chainExecutor.execute(handlers, context);

        return context.getParentOrderSn();
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

        // 再查询子订单，主要为了获取收货人等信息
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
        
        // 状态描述及剩余时间计算
        for (OrderState s : OrderState.values()) {
            if (s.getCode().equals(order.getStatus())) {
                vo.setStatusDesc(s.getDesc());
                break;
            }
        }
        
        // 计算剩余支付时间（10分钟 = 600000 毫秒）
        if ((OrderState.CREATED.getCode().equals(order.getStatus())
                || OrderState.PAYING.getCode().equals(order.getStatus())
                || OrderState.CLOSING.getCode().equals(order.getStatus()))
                && order.getCreateTime() != null) {
            LocalDateTime expireTime = order.getCreateTime().plusMinutes(10);
            // 修复计算时间差逻辑，计算从现在到过期时间的毫秒数
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
        // 1. 查询父订单表
        LambdaQueryWrapper<ParentOrder> parentQuery = new LambdaQueryWrapper<>();
        parentQuery.eq(ParentOrder::getUserId, userId);
        if (status != null) {
            // 前端“待付款”标签传 0：这里兼容把“支付中”也纳入同一标签查询结果。
            if (OrderState.CREATED.getCode().equals(status)) {
                parentQuery.in(ParentOrder::getStatus, Arrays.asList(OrderState.CREATED.getCode(), OrderState.PAYING.getCode()));
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

        // 按 parentOrderSn 进行分组
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
            
            // 按创建时间降序排列（取第一个作为基础信息，如收货地址等）
            childOrders.sort((o1, o2) -> o2.getCreateTime().compareTo(o1.getCreateTime()));
            Order mainOrder = childOrders.get(0);

            OrderListVO vo = new OrderListVO();
            BeanUtils.copyProperties(mainOrder, vo);
            
            // 覆盖一些关键字段为聚合后的值
            vo.setOrderSn(parentOrderSn); // 前端使用 orderSn 进行展示和支付，这里直接替换为 parentOrderSn
            vo.setId(parentOrder.getId()); // 使用父订单ID
            vo.setTotalAmount(parentOrder.getTotalAmount());
            vo.setPayAmount(parentOrder.getPayAmount());
            vo.setStatus(parentOrder.getStatus());
            vo.setCreateTime(parentOrder.getCreateTime());
            
            // 状态描述
            for (OrderState s : OrderState.values()) {
                if (s.getCode().equals(parentOrder.getStatus())) {
                    vo.setStatusDesc(s.getDesc());
                    break;
                }
            }

            // 如果只有一个子订单，就显示真实店铺名；如果有多个，就显示 "多店合并订单"
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

            // 计算剩余支付时间（10分钟）
            if ((OrderState.CREATED.getCode().equals(parentOrder.getStatus())
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
        LocalDateTime timeoutTime = LocalDateTime.now().minusMinutes(10);
        Set<String> affectedParentOrderSns = new HashSet<>();

        List<Order> needSoftClosing = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .in(Order::getStatus, Arrays.asList(OrderState.CREATED.getCode(), OrderState.PAYING.getCode()))
                .le(Order::getCreateTime, timeoutTime));
        if (needSoftClosing != null && !needSoftClosing.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            for (Order order : needSoftClosing) {
                // 严格由状态机驱动 CREATED/PAYING -> CLOSING，禁止手工改状态字段。
                boolean accepted = sendOrderEventWithDbState(order, OrderEvent.START_CLOSE, null, OrderExceptionEnum.ORDER_STATUS_ERROR);
                if (accepted) {
                    if (StringUtils.hasText(order.getReservationNo())) {
                        orderInventoryService.extendExpireTime(order.getReservationNo(), now.plusMinutes(2));
                    }
                    if (StringUtils.hasText(order.getParentOrderSn())) {
                        affectedParentOrderSns.add(order.getParentOrderSn());
                    }
                }
            }
        }

        List<Order> needCloseConfirm = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderState.CLOSING.getCode())
                .le(Order::getUpdateTime, LocalDateTime.now().minusMinutes(2)));
        if (needCloseConfirm != null && !needCloseConfirm.isEmpty()) {
            for (Order order : needCloseConfirm) {
                Order latest = orderMapper.selectById(order.getId());
                if (latest == null || !OrderState.CLOSING.getCode().equals(latest.getStatus())) {
                    continue;
                }
                boolean accepted = sendOrderEventWithDbState(latest, OrderEvent.CANCEL, null, OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
                if (!accepted) {
                    log.warn("状态机拒绝取消事件，订单号: {}", latest.getOrderSn());
                    continue;
                }
                if (StringUtils.hasText(latest.getParentOrderSn())) {
                    affectedParentOrderSns.add(latest.getParentOrderSn());
                }
                log.info("关闭确认完成，订单已关闭，订单号: {}", latest.getOrderSn());
            }
        }

        affectedParentOrderSns.forEach(this::recomputeParentOrderStatus);
    }

    /**
     * 父单状态兜底对账任务入口：
     * 用于修复极端并发或异常中断导致的父子状态短暂不一致。
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
            recomputeParentOrderStatus(parentOrder.getParentOrderSn());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            return;
        }
        if (isPaidOrAfter(order.getStatus()) || OrderState.CLOSED.getCode().equals(order.getStatus())) {
            return;
        }
        if (OrderState.PAY_RECOVERING.getCode().equals(order.getStatus())
                || OrderState.REFUNDING.getCode().equals(order.getStatus())
                || OrderState.REFUNDED.getCode().equals(order.getStatus())
                || OrderState.REFUND_FAILED.getCode().equals(order.getStatus())) {
            return;
        }
        if (!OrderState.CREATED.getCode().equals(order.getStatus()) && !OrderState.PAYING.getCode().equals(order.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        // 严格由状态机驱动 CREATED/PAYING -> CLOSING，禁止手工改状态字段。
        boolean accepted = sendOrderEventWithDbState(order, OrderEvent.START_CLOSE, null, OrderExceptionEnum.ORDER_STATUS_ERROR);
        if (!accepted) {
            return;
        }
        if (StringUtils.hasText(order.getReservationNo())) {
            orderInventoryService.extendExpireTime(order.getReservationNo(), now.plusMinutes(2));
        }
        recomputeParentOrderStatus(order.getParentOrderSn());
        log.info("订单进入关闭确认中，订单号: {}", order.getOrderSn());
    }

    private boolean isPaidOrAfter(Integer status) {
        return OrderState.PAID.getCode().equals(status)
                || OrderState.DELIVERED.getCode().equals(status)
                || OrderState.COMPLETED.getCode().equals(status)
                || OrderState.REFUNDED.getCode().equals(status);
    }

    /**
     * 状态机统一发送入口：
     * 1) 先把状态机恢复到数据库当前状态
     * 2) 再发送业务事件，确保流转校验基于真实状态而非初始状态
     */
    private boolean sendOrderEventWithDbState(Order order, OrderEvent event, LocalDateTime paymentTime, OrderExceptionEnum errorEnum) {
        // 始终以数据库最新状态作为状态机的 source，避免调用方传入对象过期导致并发误判。
        Order latest = orderMapper.selectById(order.getId());
        if (latest == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        StateMachine<OrderState, OrderEvent> stateMachine = orderStateMachineFactory.getStateMachine(latest.getId().toString());
        stateMachine.getStateMachineAccessor().doWithAllRegions(access -> access.addStateMachineInterceptor(orderStateChangeInterceptor));
        stateMachine.stop();
        stateMachine.getStateMachineAccessor().doWithAllRegions(access ->
                access.resetStateMachine(new DefaultStateMachineContext<>(toOrderState(latest.getStatus()), null, null, null)));
        stateMachine.start();

        MessageBuilder<OrderEvent> builder = MessageBuilder.withPayload(event).setHeader("orderId", latest.getId());
        if (paymentTime != null) {
            builder.setHeader("paymentTime", paymentTime);
        }
        boolean accepted = stateMachine.sendEvent(builder.build());
        if (stateMachine.hasStateMachineError()) {
            log.error("状态机执行异常，订单ID: {}, event={}", latest.getId(), event);
            ExceptionUtil.throwException(errorEnum);
        }
        return accepted;
    }

    private OrderState toOrderState(Integer statusCode) {
        for (OrderState value : OrderState.values()) {
            if (value.getCode().equals(statusCode)) {
                return value;
            }
        }
        log.error("未知订单状态编码，statusCode={}", statusCode);
        ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        return OrderState.INVALID;
    }

    /**
     * 父单状态不再由任一子单直接覆盖，而是由全部子单状态聚合计算得出。
     */
    private void recomputeParentOrderStatus(String parentOrderSn) {
        if (!StringUtils.hasText(parentOrderSn)) {
            return;
        }
        ParentOrder parentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, parentOrderSn));
        if (parentOrder == null) {
            return;
        }
        List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getParentOrderSn, parentOrderSn));
        if (childOrders == null || childOrders.isEmpty()) {
            return;
        }
        Integer targetStatus = calculateParentStatus(childOrders.stream().map(Order::getStatus).collect(Collectors.toList()));
        if (targetStatus == null || Objects.equals(parentOrder.getStatus(), targetStatus)) {
            return;
        }
        parentOrderMapper.update(null, new LambdaUpdateWrapper<ParentOrder>()
                .set(ParentOrder::getStatus, targetStatus)
                .set(ParentOrder::getUpdateTime, LocalDateTime.now())
                .eq(ParentOrder::getId, parentOrder.getId())
                .eq(ParentOrder::getStatus, parentOrder.getStatus()));
    }

    /**
     * 父单聚合规则：
     * 全部关闭->关闭；全部完成->完成；全部已发货或已完成且至少有已发货->已发货；
     * 不存在待付款且至少有已支付/已发货/已完成->已支付；其余保持待付款。
     */
    private Integer calculateParentStatus(List<Integer> childStatuses) {
        if (childStatuses == null || childStatuses.isEmpty()) {
            return null;
        }
        boolean allClosed = childStatuses.stream().allMatch(s -> OrderState.CLOSED.getCode().equals(s));
        if (allClosed) {
            return OrderState.CLOSED.getCode();
        }
        boolean allCompleted = childStatuses.stream().allMatch(s -> OrderState.COMPLETED.getCode().equals(s));
        if (allCompleted) {
            return OrderState.COMPLETED.getCode();
        }
        boolean allDeliveredOrCompleted = childStatuses.stream().allMatch(s ->
                OrderState.DELIVERED.getCode().equals(s) || OrderState.COMPLETED.getCode().equals(s));
        boolean hasDelivered = childStatuses.stream().anyMatch(s -> OrderState.DELIVERED.getCode().equals(s));
        if (allDeliveredOrCompleted && hasDelivered) {
            return OrderState.DELIVERED.getCode();
        }
        boolean hasCreated = childStatuses.stream().anyMatch(s -> OrderState.CREATED.getCode().equals(s));
        boolean hasPaying = childStatuses.stream().anyMatch(s -> OrderState.PAYING.getCode().equals(s));
        boolean hasClosing = childStatuses.stream().anyMatch(s -> OrderState.CLOSING.getCode().equals(s));
        boolean hasPayRecovering = childStatuses.stream().anyMatch(s -> OrderState.PAY_RECOVERING.getCode().equals(s));
        boolean hasRefunding = childStatuses.stream().anyMatch(s -> OrderState.REFUNDING.getCode().equals(s));
        boolean hasRefundFailed = childStatuses.stream().anyMatch(s -> OrderState.REFUND_FAILED.getCode().equals(s));
        boolean allRefunded = childStatuses.stream().allMatch(s -> OrderState.REFUNDED.getCode().equals(s));
        boolean hasPaidOrAfter = childStatuses.stream().anyMatch(s ->
                OrderState.PAID.getCode().equals(s)
                        || OrderState.DELIVERED.getCode().equals(s)
                        || OrderState.COMPLETED.getCode().equals(s)
                        || OrderState.REFUNDED.getCode().equals(s));
        if (hasRefunding) {
            return OrderState.REFUNDING.getCode();
        }
        if (allRefunded) {
            return OrderState.REFUNDED.getCode();
        }
        if (hasRefundFailed) {
            return OrderState.REFUND_FAILED.getCode();
        }
        if (!hasCreated && hasPaidOrAfter) {
            return OrderState.PAID.getCode();
        }
        if (!hasPaidOrAfter && hasPayRecovering) {
            return OrderState.PAY_RECOVERING.getCode();
        }
        if (!hasPaidOrAfter && hasClosing) {
            return OrderState.CLOSING.getCode();
        }
        if (!hasPaidOrAfter && hasPaying) {
            return OrderState.PAYING.getCode();
        }
        return OrderState.CREATED.getCode();
    }

    /**
     * 二次确认校验：
     * 1) token 必须存在且未过期
     * 2) 当前实时快照指纹必须与确认阶段一致
     */
    private void validateConfirmToken(Long userId, OrderSubmitReq req) {
        if (req == null || !StringUtils.hasText(req.getConfirmToken())) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_CONFIRM_INVALID);
        }
        String key = RedisKeyEnum.ORDER_CONFIRM_TOKEN.getKey(userId, req.getConfirmToken());
        String fingerprint = orderRedisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(fingerprint)) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_CONFIRM_INVALID);
        }
        PrecheckSnapshot snapshot = buildPrecheckSnapshot(userId, req);
        if (!snapshot.ready || !fingerprint.equals(snapshot.fingerprint)) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_CONFIRM_INVALID);
        }
        orderRedisTemplate.delete(key);
    }

    /**
     * 构建实时校验快照：
     * 以DB为准检查地址、购物车、商品状态、库存、价格，并生成前端差异明细与指纹。
     */
    private PrecheckSnapshot buildPrecheckSnapshot(Long userId, OrderSubmitReq req) {
        LambdaQueryWrapper<Address> addressQuery = new LambdaQueryWrapper<>();
        addressQuery.eq(Address::getId, req.getAddressId()).eq(Address::getUserId, userId);
        Address address = addressMapper.selectOne(addressQuery);
        if (address == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ADDRESS_NOT_EXIST);
        }

        LambdaQueryWrapper<CartItem> cartQuery = new LambdaQueryWrapper<>();
        cartQuery.eq(CartItem::getUserId, userId).eq(CartItem::getChecked, 1);
        if (req.getShopId() != null) {
            cartQuery.eq(CartItem::getShopId, req.getShopId());
        }
        List<CartItem> cartItems = cartItemMapper.selectList(cartQuery);
        if (cartItems == null || cartItems.isEmpty()) {
            ExceptionUtil.throwException(OrderExceptionEnum.CART_EMPTY);
        }

        List<Long> productIds = cartItems.stream().map(CartItem::getProductId).distinct().collect(Collectors.toList());
        Map<Long, Product> productMap = new HashMap<>();
        if (!productIds.isEmpty()) {
            productMap = productMapper.selectBatchIds(productIds).stream().collect(Collectors.toMap(Product::getId, p -> p));
        }

        List<OrderConfirmVO.ItemResult> results = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        boolean ready = true;
        StringBuilder fingerprintSource = new StringBuilder();
        fingerprintSource.append("u=").append(userId).append("|a=").append(req.getAddressId()).append("|s=").append(req.getShopId());

        List<CartItem> sortedItems = new ArrayList<>(cartItems);
        sortedItems.sort(Comparator.comparing(CartItem::getId));
        for (CartItem item : sortedItems) {
            Product product = productMap.get(item.getProductId());
            OrderConfirmVO.ItemResult result = new OrderConfirmVO.ItemResult();
            result.setCartItemId(item.getId());
            result.setProductId(item.getProductId());
            result.setShopId(item.getShopId());
            result.setProductName(item.getProductName());
            result.setQuantity(item.getQuantity());
            result.setCartPrice(item.getPriceSnapshot());
            result.setValid(true);

            if (product == null) {
                result.setValid(false);
                result.setReasonCode("PRODUCT_NOT_EXIST");
                result.setReasonMsg("商品不存在");
                ready = false;
                results.add(result);
                fingerprintSource.append("|i=").append(item.getId()).append(":missing");
                continue;
            }

            result.setProductName(product.getName());
            result.setCurrentPrice(product.getPrice());
            result.setAvailableStock(product.getStock());

            if (!Objects.equals(product.getStatus(), 1)) {
                result.setValid(false);
                result.setReasonCode("PRODUCT_OFFLINE");
                result.setReasonMsg("商品已下架");
                ready = false;
            } else if (item.getQuantity() == null || item.getQuantity() <= 0) {
                result.setValid(false);
                result.setReasonCode("INVALID_QUANTITY");
                result.setReasonMsg("购买数量非法");
                ready = false;
            } else if (product.getStock() == null || product.getStock() < item.getQuantity()) {
                result.setValid(false);
                result.setReasonCode("STOCK_NOT_ENOUGH");
                result.setReasonMsg("库存不足");
                ready = false;
            } else if (item.getPriceSnapshot() == null || product.getPrice() == null || item.getPriceSnapshot().compareTo(product.getPrice()) != 0) {
                result.setValid(false);
                result.setReasonCode("PRICE_CHANGED");
                result.setReasonMsg("商品价格已变动");
                ready = false;
            }

            if (product.getPrice() != null && item.getQuantity() != null && item.getQuantity() > 0) {
                totalAmount = totalAmount.add(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            }

            results.add(result);
            fingerprintSource.append("|i=").append(item.getId())
                    .append(":p=").append(item.getProductId())
                    .append(",q=").append(item.getQuantity())
                    .append(",cp=").append(item.getPriceSnapshot())
                    .append(",np=").append(product.getPrice())
                    .append(",st=").append(product.getStatus())
                    .append(",sk=").append(product.getStock());
        }

        PrecheckSnapshot snapshot = new PrecheckSnapshot();
        snapshot.ready = ready;
        snapshot.totalAmount = totalAmount;
        snapshot.items = results;
        snapshot.fingerprint = sha256Hex(fingerprintSource.toString());
        return snapshot;
    }

    private String sha256Hex(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static class PrecheckSnapshot {
        private boolean ready;
        private String fingerprint;
        private BigDecimal totalAmount;
        private List<OrderConfirmVO.ItemResult> items;
    }
}
