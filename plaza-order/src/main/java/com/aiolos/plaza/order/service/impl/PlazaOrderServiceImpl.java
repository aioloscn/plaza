package com.aiolos.plaza.order.service.impl;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.mq.constant.OrderMqConstants;
import com.aiolos.plaza.order.config.AlipayConfig;
import com.aiolos.plaza.model.po.MqLocalMessage;
import com.aiolos.plaza.service.MqLocalMessageService;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeWapPayModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
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
import com.aiolos.plaza.order.model.bo.OrderSubmitReq;
import com.aiolos.plaza.order.service.PlazaOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import com.aiolos.plaza.mapper.ShopMapper;
import com.aiolos.plaza.model.po.Shop;
import com.aiolos.plaza.order.model.vo.OrderItemVO;
import com.aiolos.plaza.order.model.vo.OrderListVO;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import com.aiolos.plaza.enums.OrderEvent;

import com.alibaba.fastjson.JSON;

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
    private StateMachineFactory<OrderState, OrderEvent> orderStateMachineFactory;

    @Autowired
    private MqLocalMessageService mqLocalMessageService;

    @Autowired
    private AlipayConfig alipayConfig;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String submit(Long userId, OrderSubmitReq req) {
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
        if (OrderState.CREATED.getCode().equals(order.getStatus()) && order.getCreateTime() != null) {
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
            parentQuery.eq(ParentOrder::getStatus, status);
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
            if (OrderState.CREATED.getCode().equals(parentOrder.getStatus()) && parentOrder.getCreateTime() != null) {
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
        // 查找创建时间在10分钟前，且状态为待付款(0)的订单
        LocalDateTime timeoutTime = LocalDateTime.now().minusMinutes(10);
        LambdaQueryWrapper<Order> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Order::getStatus, OrderState.CREATED.getCode());
        queryWrapper.le(Order::getCreateTime, timeoutTime);
        
        List<Order> timeoutOrders = orderMapper.selectList(queryWrapper);
        if (timeoutOrders == null || timeoutOrders.isEmpty()) {
            return;
        }

        for (Order order : timeoutOrders) {
            // 更新订单状态为已关闭
            // 使用状态机触发取消事件，而不再直接修改状态
            StateMachine<OrderState, OrderEvent> stateMachine = orderStateMachineFactory.getStateMachine(order.getId().toString());
            // 手动注册持久化拦截器，否则状态流转后不会更新DB
            stateMachine.getStateMachineAccessor().doWithAllRegions(access -> access.addStateMachineInterceptor(orderStateChangeInterceptor));
            stateMachine.start(); // 必须启动状态机
            
            Message<OrderEvent> message = MessageBuilder.withPayload(OrderEvent.CANCEL)
                    .setHeader("orderId", order.getId())
                    .build();
            boolean accepted = stateMachine.sendEvent(message);
            
            // 状态机是否可以处理该事件，状态不匹配返回 false，Action抛出异常依然会返回 true
            if (!accepted) {
                log.warn("状态机拒绝取消事件，订单号: {}", order.getOrderSn());
                continue;
            }

            // 发送事件后，检查状态机内部是否发生了被吞掉的异常
            if (stateMachine.hasStateMachineError()) {
                log.error("状态机执行Action发生异常，触发事务回滚，订单号: {}", order.getOrderSn());
                ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
            }

            log.info("超时未支付订单取消成功，订单号: {}", order.getOrderSn());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || !OrderState.CREATED.getCode().equals(order.getStatus())) {
            // 订单不存在或不是待支付状态，无需取消
            return;
        }

        // 使用状态机触发取消事件
        StateMachine<OrderState, OrderEvent> stateMachine = orderStateMachineFactory.getStateMachine(order.getId().toString());
        // 手动注册持久化拦截器，否则状态流转后不会更新DB
        stateMachine.getStateMachineAccessor().doWithAllRegions(access -> access.addStateMachineInterceptor(orderStateChangeInterceptor));
        stateMachine.start(); // 必须启动状态机
        
        Message<OrderEvent> message = MessageBuilder.withPayload(OrderEvent.CANCEL)
                .setHeader("orderId", order.getId())
                .build();
        boolean accepted = stateMachine.sendEvent(message);
        
        if (!accepted) {
            log.warn("状态机拒绝取消事件，订单号: {}", order.getOrderSn());
            return;
        }

        // 发送事件后，检查状态机内部是否发生了被吞掉的异常
        if (stateMachine.hasStateMachineError()) {
            log.error("状态机执行Action发生异常，触发事务回滚，订单号: {}", order.getOrderSn());
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
        }

        log.info("延迟消息触发未支付订单取消成功，订单号: {}", order.getOrderSn());
    }

    @Override
    public String pay(Long userId, String orderSn, Integer payType, boolean isMobile) {
        // 1. 查询订单
        // 根据业务逻辑，如果是合并支付，应该是 parentOrderSn
        ParentOrder parentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                .eq(ParentOrder::getParentOrderSn, orderSn)
                .eq(ParentOrder::getUserId, userId));

        if (parentOrder == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }

        // 2. 校验状态
        if (!OrderState.CREATED.getCode().equals(parentOrder.getStatus())) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }

        // 3. 调用支付宝 SDK 生成支付表单
        try {
            AlipayClient alipayClient = new DefaultAlipayClient(
                    alipayConfig.getGatewayUrl(),
                    alipayConfig.getAppId(),
                    alipayConfig.getMerchantPrivateKey(),
                    alipayConfig.getFormat(),
                    alipayConfig.getCharset(),
                    alipayConfig.getAlipayPublicKey(),
                    alipayConfig.getSignType());

            if (isMobile) {
                AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
                AlipayTradeWapPayModel model = new AlipayTradeWapPayModel();
                model.setOutTradeNo(orderSn);
                model.setSubject("Plaza商城订单-" + orderSn);
                model.setTotalAmount(parentOrder.getPayAmount().toString());
                model.setBody("Plaza商城订单支付");
                model.setProductCode("QUICK_WAP_WAY");
                request.setBizModel(model);
                request.setNotifyUrl(alipayConfig.getNotifyUrl());
                request.setReturnUrl(alipayConfig.getReturnUrl());
                return alipayClient.pageExecute(request).getBody();
            } else {
                AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
                AlipayTradePagePayModel model = new AlipayTradePagePayModel();
                model.setOutTradeNo(orderSn);
                model.setSubject("Plaza商城订单-" + orderSn);
                model.setTotalAmount(parentOrder.getPayAmount().toString());
                model.setBody("Plaza商城订单支付");
                model.setProductCode("FAST_INSTANT_TRADE_PAY");
                request.setBizModel(model);
                request.setNotifyUrl(alipayConfig.getNotifyUrl());
                request.setReturnUrl(alipayConfig.getReturnUrl());
                return alipayClient.pageExecute(request).getBody();
            }
        } catch (Exception e) {
            log.error("生成支付表单失败", e);
            ExceptionUtil.throwException(OrderExceptionEnum.CREATE_PAY_FORM_FAIL);
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String payNotify(Map<String, String> params) {
        log.info("收到支付宝回调通知: {}", params);
        try {
            // 1. 验签 (跳过 AppID 校验，因为沙箱环境可能不一致，或者需要配置)
            // 在生产环境必须开启
            boolean signVerified = true; 
            try {
                signVerified = AlipaySignature.rsaCheckV1(
                    params,
                    alipayConfig.getAlipayPublicKey(),
                    alipayConfig.getCharset(),
                    alipayConfig.getSignType());
            } catch (Exception e) {
                log.error("支付宝验签异常", e);
                return "fail";
            }

            if (!signVerified) {
                log.error("支付宝回调验签失败");
                return "fail";
            }

            // 2. 校验参数
            String outTradeNo = params.get("out_trade_no");
            String tradeStatus = params.get("trade_status");
            String totalAmount = params.get("total_amount");
            String appId = params.get("app_id");

            if (!alipayConfig.getAppId().equals(appId)) {
                log.error("支付宝回调AppID不匹配");
                return "fail";
            }

            if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
                return "success"; // 状态不对也返回 success 防止支付宝重试，因为我们只处理成功
            }

            // 3. 查询订单
            ParentOrder parentOrder = parentOrderMapper.selectOne(new LambdaQueryWrapper<ParentOrder>()
                    .eq(ParentOrder::getParentOrderSn, outTradeNo));

            if (parentOrder == null) {
                log.error("支付宝回调订单不存在: {}", outTradeNo);
                return "fail";
            }

            // 4. 校验金额 (注意 BigDecimal 比较)
            if (parentOrder.getPayAmount().compareTo(new BigDecimal(totalAmount)) != 0) {
                log.error("支付宝回调金额不匹配: 订单金额={}, 回调金额={}", parentOrder.getPayAmount(), totalAmount);
                return "fail";
            }

            // 5. 幂等处理：如果订单已经是支付状态，直接返回 success
            if (OrderState.PAID.getCode().equals(parentOrder.getStatus()) || 
                OrderState.DELIVERED.getCode().equals(parentOrder.getStatus()) ||
                OrderState.COMPLETED.getCode().equals(parentOrder.getStatus())) {
                log.info("订单已支付，忽略回调: {}", outTradeNo);
                return "success";
            }

            // 6. 更新父订单状态
            parentOrder.setStatus(OrderState.PAID.getCode());
            parentOrder.setPaymentTime(LocalDateTime.now()); // 更新支付时间
            parentOrder.setTradeNo(params.get("trade_no")); // 记录第三方流水号
            parentOrder.setBuyerId(params.get("buyer_id")); // 记录买家账号
            parentOrder.setUpdateTime(LocalDateTime.now());
            parentOrderMapper.updateById(parentOrder);

            // 7. 更新子订单状态（使用状态机处理，确保业务流程完整性）
            List<Order> childOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                    .eq(Order::getParentOrderSn, outTradeNo));
            
            for (Order child : childOrders) {
                // 仅处理待支付状态的订单
                if (OrderState.CREATED.getCode().equals(child.getStatus())) {
                    // 获取对应订单的状态机
                    StateMachine<OrderState, OrderEvent> stateMachine = orderStateMachineFactory.getStateMachine(child.getId().toString());
                    // 配置状态机拦截器，用于持久化状态变更
                    stateMachine.getStateMachineAccessor().doWithAllRegions(access -> access.addStateMachineInterceptor(orderStateChangeInterceptor));
                    stateMachine.start();
                    
                    // 构建支付事件消息
                    Message<OrderEvent> message = MessageBuilder.withPayload(OrderEvent.PAY)
                            .setHeader("orderId", child.getId())
                            .setHeader("paymentTime", parentOrder.getPaymentTime()) // 传递支付时间
                            .build();
                    
                    // 发送事件，状态机自动处理状态流转和后续动作
                    boolean accepted = stateMachine.sendEvent(message);
                    if (!accepted) {
                        log.warn("订单状态机拒绝支付事件，订单ID: {}, 当前状态: {}", child.getId(), child.getStatus());
                    }
                }
            }

            // 8. 写入本地消息表 (Outbox)，通知下游
            MqLocalMessage localMsg = new MqLocalMessage();
            localMsg.setTopic(OrderMqConstants.BINDING_ORDER_PAID_OUT);
            localMsg.setContent(JSON.toJSONString(parentOrder));
            localMsg.setState(0);
            localMsg.setBusinessKey(outTradeNo);
            localMsg.setCreateTime(LocalDateTime.now());
            localMsg.setUpdateTime(LocalDateTime.now());
            mqLocalMessageService.save(localMsg);

            return "success";
        } catch (Exception e) {
            log.error("支付宝回调处理异常", e);
            return "fail";
        }
    }

    /**
     * 生成订单号：时间戳 + 随机数
     * @param prefix 订单号前缀（如：P-父订单，D-子订单）
     */
    private String generateOrderSn(String prefix) {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        int random = (int) (Math.random() * 9000 + 1000);
        return prefix + dateStr + random;
    }
}
