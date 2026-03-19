package com.aiolos.plaza.order.service.impl;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.AddressMapper;
import com.aiolos.plaza.mapper.CartItemMapper;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.model.po.Address;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.mq.message.CartAsyncSaveMessage;
import com.aiolos.plaza.mq.constant.CartMqConstants;
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
import com.aiolos.plaza.order.model.dto.CartItemDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.config.OrderStateChangeInterceptor;
import com.aiolos.plaza.order.model.bo.OrderSubmitReq;
import com.aiolos.plaza.mq.message.StockDeductMessage;
import com.aiolos.plaza.order.mq.producer.OrderMessageProducer;
import com.aiolos.plaza.order.service.PlazaOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import com.aiolos.plaza.mapper.ProductMapper;
import com.aiolos.plaza.model.po.Product;
import com.aiolos.plaza.mapper.ShopMapper;
import com.aiolos.plaza.model.po.Shop;
import com.aiolos.plaza.order.model.vo.OrderItemVO;
import com.aiolos.plaza.order.model.vo.OrderListVO;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import com.aiolos.plaza.enums.OrderEvent;

import java.util.concurrent.TimeUnit;
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
    private CartItemMapper cartItemMapper;

    @Autowired
    private AddressMapper addressMapper;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    @Qualifier("cartRedisTemplate")
    private StringRedisTemplate cartRedisTemplate;

    @Autowired
    @Qualifier("shopRedisTemplate")
    private StringRedisTemplate shopRedisTemplate;

    @Autowired
    private StateMachineFactory<OrderState, OrderEvent> orderStateMachineFactory;

    @Resource
    private OrderMessageProducer orderMessageProducer;

    @Autowired
    private MqLocalMessageService mqLocalMessageService;

    @Autowired
    private AlipayConfig alipayConfig;

    private DefaultRedisScript<Long> stockDeductScript;

    @Autowired
    private OrderStateChangeInterceptor orderStateChangeInterceptor;

    private static final String CART_PREFIX = "cart:";
    private static final String PRODUCT_INFO_PREFIX = "product:info:";
    private static final String PRODUCT_STOCK_PREFIX = "product:stock:";
    
    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        stockDeductScript = new DefaultRedisScript<>();
        stockDeductScript.setResultType(Long.class);
        stockDeductScript.setLocation(new ClassPathResource("lua/stock_deduct.lua"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String submit(Long userId, OrderSubmitReq req) {
        // 1. 获取收货地址
        Address address = addressMapper.selectById(req.getAddressId());
        if (address == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ADDRESS_NOT_EXIST);
        }

        // 2. 获取购物车选中商品（优先从 Redis 获取）
        List<CartItem> cartItems = new ArrayList<>();
        String cartKey = CART_PREFIX + "user:" + userId;
        Map<Object, Object> redisCart = cartRedisTemplate.opsForHash().entries(cartKey);

        if (redisCart != null && !redisCart.isEmpty()) {
            for (Object json : redisCart.values()) {
                try {
                    CartItemDTO cartItemDto = objectMapper.readValue(json.toString(), CartItemDTO.class);
                    // 筛选选中的商品
                    if (Boolean.TRUE.equals(cartItemDto.getChecked())) {
                        // 如果指定了店铺，则进行过滤
                        if (req.getShopId() != null && !req.getShopId().equals(cartItemDto.getShopId())) {
                            continue;
                        }
                        
                        CartItem cartItem = new CartItem();
                        BeanUtils.copyProperties(cartItemDto, cartItem);
                        cartItem.setUserId(userId);
                        // 处理特殊字段映射
                        cartItem.setProductImage(cartItemDto.getProductImage());
                        // 确保ID正确
                        cartItem.setId(cartItemDto.getId());
                        
                        cartItems.add(cartItem);
                    }
                } catch (Exception e) {
                    log.error("解析购物车Redis数据失败", e);
                }
            }
        }

        // 如果 Redis 中没有数据（异常情况），兜底查 MySQL
        if (cartItems.isEmpty() && (redisCart == null || redisCart.isEmpty())) {
            LambdaQueryWrapper<CartItem> cartQuery = new LambdaQueryWrapper<>();
            cartQuery.eq(CartItem::getUserId, userId);
            // 如果传入了 shopId（单店结算），则增加过滤条件
            if (req.getShopId() != null) {
                cartQuery.eq(CartItem::getShopId, req.getShopId());
            }
            cartQuery.eq(CartItem::getChecked, 1); // 只结算选中的商品
            cartItems = cartItemMapper.selectList(cartQuery);
        }

        if (cartItems == null || cartItems.isEmpty()) {
            ExceptionUtil.throwException(OrderExceptionEnum.CART_EMPTY);
        }

        // 3. 按店铺分组
        Map<Long, List<CartItem>> shopCartMap = Objects.requireNonNull(cartItems).stream()
                .collect(Collectors.groupingBy(CartItem::getShopId));

        // 4. 生成父订单号
        String parentOrderSn = generateOrderSn("P"); // 复用生成规则

        List<StockDeductMessage> stockDeductMessages = new ArrayList<>();
        List<Long> allCartIds = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        
        BigDecimal parentTotalAmount = BigDecimal.ZERO;

        // 5. 遍历店铺生成订单
        for (Map.Entry<Long, List<CartItem>> entry : shopCartMap.entrySet()) {
            Long shopId = entry.getKey();
            List<CartItem> shopItems = entry.getValue();

            // 生成子订单号
            String orderSn = generateOrderSn("D");
            
            BigDecimal totalAmount = BigDecimal.ZERO;

            for (CartItem item : shopItems) {
                Long productId = item.getProductId();
                allCartIds.add(item.getId());

                // 从缓存中查询商品信息
                String productJson = shopRedisTemplate.opsForValue().get(PRODUCT_INFO_PREFIX + productId);
                if (productJson == null) {
                    Product dbProduct = productMapper.selectById(productId);
                    if (dbProduct == null || dbProduct.getStatus() != 1) {
                        ExceptionUtil.throwException(OrderExceptionEnum.PRODUCT_NOT_EXIST);
                    }
                    shopRedisTemplate.opsForValue().set(PRODUCT_INFO_PREFIX + productId, JSON.toJSONString(dbProduct), 1, TimeUnit.DAYS);
                    shopRedisTemplate.opsForValue().setIfAbsent(PRODUCT_STOCK_PREFIX + productId, String.valueOf(dbProduct.getStock()));
                    productJson = JSON.toJSONString(dbProduct);
                }

                Product product = JSON.parseObject(productJson, Product.class);
                if (product.getStatus() != 1) {
                    ExceptionUtil.throwException(OrderExceptionEnum.PRODUCT_NOT_EXIST);
                }

                // 使用 Lua 脚本原子扣减缓存库存
                Long result = shopRedisTemplate.execute(stockDeductScript, 
                        Collections.singletonList(PRODUCT_STOCK_PREFIX + productId), 
                        String.valueOf(item.getQuantity()));

                if (result == -2) {
                    ExceptionUtil.throwException(OrderExceptionEnum.PRODUCT_NOT_EXIST);
                } else if (result == -1) {
                    ExceptionUtil.throwException(OrderExceptionEnum.STOCK_NOT_ENOUGH);
                }

                stockDeductMessages.add(new StockDeductMessage(productId, item.getQuantity(), orderSn));

                BigDecimal price = product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO;
                item.setPriceSnapshot(price);
                BigDecimal itemAmount = price.multiply(new BigDecimal(item.getQuantity()));
                totalAmount = totalAmount.add(itemAmount);
            }

            // 生成订单
            Order order = new Order();
            order.setOrderSn(orderSn);
            order.setParentOrderSn(parentOrderSn); // 设置父订单号
            order.setUserId(userId);
            order.setShopId(shopId);
            order.setTotalAmount(totalAmount);
            order.setPayAmount(totalAmount);
            order.setFreightAmount(BigDecimal.ZERO);
            order.setPromotionAmount(BigDecimal.ZERO);
            order.setPayType(req.getPayType());
            order.setStatus(OrderState.CREATED.getCode());
            order.setAddressId(req.getAddressId());

            order.setReceiverName(address.getName());
            order.setReceiverPhone(address.getTel());
            order.setReceiverProvince(address.getProvince());
            order.setReceiverCity(address.getCity());
            order.setReceiverRegion(address.getCounty());
            order.setReceiverDetailAddress(address.getAddressDetail());

            // 设置备注
            if (req.getShopNotes() != null && req.getShopNotes().containsKey(shopId)) {
                order.setNote(req.getShopNotes().get(shopId));
            }

            order.setDeleteStatus(0);
            order.setCreateTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());
            order.setConfirmStatus(0);

            orderMapper.insert(order);
            orderIds.add(order.getId());

            // 生成订单项
            for (CartItem cartItem : shopItems) {
                OrderItem orderItem = new OrderItem();
                orderItem.setOrderId(order.getId());
                orderItem.setOrderSn(order.getOrderSn());
                orderItem.setProductId(cartItem.getProductId());
                orderItem.setProductPic(cartItem.getProductImage());
                orderItem.setProductName(cartItem.getProductName());
                orderItem.setProductPrice(cartItem.getPriceSnapshot());
                orderItem.setProductQuantity(cartItem.getQuantity());
                
                BigDecimal price = cartItem.getPriceSnapshot() != null ? cartItem.getPriceSnapshot() : BigDecimal.ZERO;
                orderItem.setRealAmount(price.multiply(new BigDecimal(cartItem.getQuantity())));
                
                orderItemMapper.insert(orderItem);
            }
            
            parentTotalAmount = parentTotalAmount.add(totalAmount);
        }

        // 保存父订单
        ParentOrder parentOrder = new ParentOrder();
        parentOrder.setParentOrderSn(parentOrderSn);
        parentOrder.setUserId(userId);
        parentOrder.setTotalAmount(parentTotalAmount);
        parentOrder.setPayAmount(parentTotalAmount);
        parentOrder.setStatus(OrderState.CREATED.getCode());
        parentOrder.setPayType(req.getPayType());
        parentOrder.setDeleteStatus(0);
        parentOrder.setCreateTime(LocalDateTime.now());
        parentOrder.setUpdateTime(LocalDateTime.now());
        parentOrderMapper.insert(parentOrder);

        // 6. 物理清除订单服务本地的 MySQL 购物车记录
        // MQ 消费端的删除会变成一次幂等操作（如果已经删了，MyBatisPlus 的 remove 也不会报错）。
        if (!allCartIds.isEmpty()) {
            cartItemMapper.deleteBatchIds(allCartIds);
        }

        // 清除 Redis 缓存中的商品
        try {
            Object[] productIds = cartItems.stream()
                .map(item -> String.valueOf(item.getProductId()))
                .toArray();
            cartRedisTemplate.boundHashOps(cartKey).delete(productIds);
            
            // 检查购物车是否已空，如果为空则设置短期标记阻止并发回源
            Long size = cartRedisTemplate.boundHashOps(cartKey).size();
            if (size == null || size == 0) {
                String emptyMarkKey = "cart:empty_mark:user:" + userId;
                cartRedisTemplate.opsForValue().set(emptyMarkKey, "1", 60, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.error("清除Redis购物车缓存失败: userId={}", userId, e);
        }

        // 7. 写入本地消息表（Outbox模式），异步发送 MQ 消息去扣减数据库库存
        List<MqLocalMessage> localMessages = new ArrayList<>();
        for (StockDeductMessage msg : stockDeductMessages) {
            MqLocalMessage localMsg = new MqLocalMessage();
            localMsg.setTopic(OrderMqConstants.BINDING_STOCK_DEDUCT_OUT);
            localMsg.setContent(JSON.toJSONString(msg));
            localMsg.setState(0); // 0:新建
            localMsg.setRetryCount(0);
            localMsg.setBusinessKey(msg.getOrderSn());
            localMsg.setCreateTime(LocalDateTime.now());
            localMsg.setUpdateTime(LocalDateTime.now());
            localMessages.add(localMsg);
        }

        // 7.1 写入本地消息表（Outbox模式），发送异步消息清除购物车 MySQL 数据（防幽灵数据）
        if (!allCartIds.isEmpty()) {
            for (CartItem item : cartItems) {
                CartAsyncSaveMessage cartMsg = new CartAsyncSaveMessage();
                cartMsg.setUserId(userId);
                cartMsg.setProductId(item.getProductId());
                cartMsg.setOperateType(2); // 2: 删除
                
                MqLocalMessage localMsg = new MqLocalMessage();
                localMsg.setTopic(CartMqConstants.BINDING_CART_SAVE_OUT);
                localMsg.setContent(JSON.toJSONString(cartMsg));
                localMsg.setState(0); // 0:新建
                localMsg.setRetryCount(0);
                localMsg.setBusinessKey(String.valueOf(userId));
                localMsg.setCreateTime(LocalDateTime.now());
                localMsg.setUpdateTime(LocalDateTime.now());
                localMessages.add(localMsg);
            }
        }
        
        // 批量保存本地消息
        if (!localMessages.isEmpty()) {
            mqLocalMessageService.saveBatch(localMessages);
        }

        // 8. 发送延迟消息（这个可以允许直接发送，因为延迟消息本身就是异步的且对一致性要求稍低，或者也可以改为本地消息表，这里先保持原样，或者也改为本地消息表）
        // 考虑到延迟消息是 RocketMQ 的特性，本地消息表重发时也需要支持延迟发送，这里为了简单起见，延迟消息暂时不改，因为如果事务回滚了，延迟消息发出去也就是多了一个无效检查而已，影响不大。
        // 但为了严谨，也可以纳入。不过这里只改前两个关键的。
        for (Long orderId : orderIds) {
            orderMessageProducer.sendOrderTimeoutMessage(orderId, 14);
        }

        return parentOrderSn;
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
            stateMachine.sendEvent(message);

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
