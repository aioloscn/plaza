package com.aiolos.plaza.order.service.impl;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.AddressMapper;
import com.aiolos.plaza.mapper.CartItemMapper;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.model.po.Address;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.order.dto.OrderSubmitReq;
import com.aiolos.plaza.order.service.PlazaOrderService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import com.aiolos.plaza.mapper.ShopMapper;
import com.aiolos.plaza.model.po.Shop;
import com.aiolos.plaza.order.vo.OrderItemVO;
import com.aiolos.plaza.order.vo.OrderListVO;
import java.util.ArrayList;
import java.util.Map;

@Slf4j
@Service
public class PlazaOrderServiceImpl implements PlazaOrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private CartItemMapper cartItemMapper;

    @Autowired
    private AddressMapper addressMapper;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    @Qualifier("cartRedisTemplate")
    private StringRedisTemplate cartRedisTemplate;

    @Autowired
    @Qualifier("shopRedisTemplate")
    private StringRedisTemplate shopRedisTemplate;

    private static final String CART_PREFIX = "cart:";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submit(Long userId, OrderSubmitReq req) {
        // 1. 获取收货地址
        Address address = addressMapper.selectById(req.getAddressId());
        if (address == null) {
            throw new RuntimeException("收货地址不存在");
        }

        // 2. 获取购物车选中商品（这里简化为该店铺下所有商品，实际应根据勾选状态）
        // 假设前端传递了 shopId，我们查询该用户该店铺下的所有购物车商品
        // 实际场景中通常只结算选中的商品
        QueryWrapper<CartItem> cartQuery = new QueryWrapper<>();
        cartQuery.eq("user_id", userId);
        cartQuery.eq("shop_id", req.getShopId());
        cartQuery.eq("checked", 1); // 只结算选中的商品
        List<CartItem> cartItems = cartItemMapper.selectList(cartQuery);

        if (cartItems == null || cartItems.isEmpty()) {
            ExceptionUtil.throwException(OrderExceptionEnum.CART_EMPTY);
        }

        // 3. 计算金额
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CartItem item : cartItems) {
            BigDecimal price = item.getPriceSnapshot() != null ? item.getPriceSnapshot() : BigDecimal.ZERO;
            BigDecimal itemAmount = price.multiply(new BigDecimal(item.getQuantity()));
            totalAmount = totalAmount.add(itemAmount);
        }

        // 4. 生成订单
        Order order = new Order();
        order.setOrderSn(generateOrderSn());
        order.setUserId(userId);
        order.setShopId(req.getShopId());
        order.setTotalAmount(totalAmount);
        order.setPayAmount(totalAmount); // 暂时无优惠
        order.setFreightAmount(BigDecimal.ZERO); // 暂时无运费
        order.setPromotionAmount(BigDecimal.ZERO);
        order.setPayType(req.getPayType());
        order.setStatus(OrderState.CREATED.getCode()); // 状态机初始状态
        order.setAddressId(req.getAddressId());
        
        // 填充收货人信息
        order.setReceiverName(address.getName());
        order.setReceiverPhone(address.getTel());
        order.setReceiverProvince(address.getProvince());
        order.setReceiverCity(address.getCity());
        order.setReceiverRegion(address.getCounty());
        order.setReceiverDetailAddress(address.getAddressDetail());
        
        order.setNote(req.getNote());
        order.setDeleteStatus(0);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        order.setConfirmStatus(0); // 初始化确认收货状态

        orderMapper.insert(order);

        // 5. 生成订单项
        List<Long> cartIds = cartItems.stream().map(CartItem::getId).collect(Collectors.toList());
        for (CartItem cartItem : cartItems) {
            OrderItem orderItem = new OrderItem();
            orderItem.setOrderId(order.getId());
            orderItem.setOrderSn(order.getOrderSn());
            orderItem.setProductId(cartItem.getProductId());
            orderItem.setProductPic(cartItem.getProductImage());
            orderItem.setProductName(cartItem.getProductName());
            orderItem.setProductPrice(cartItem.getPriceSnapshot());
            orderItem.setProductQuantity(cartItem.getQuantity());
            // orderItem.setProductSkuId(cartItem.getProductSkuId()); // CartItem 无 SKU 信息
            // orderItem.setProductCategoryId(cartItem.getProductCategoryId()); // 假设 CartItem 有
            
            // 计算分摊金额（简单处理）
            BigDecimal price = cartItem.getPriceSnapshot() != null ? cartItem.getPriceSnapshot() : BigDecimal.ZERO;
            orderItem.setRealAmount(price.multiply(new BigDecimal(cartItem.getQuantity())));
            
            orderItemMapper.insert(orderItem);
        }

        // 6. 清除购物车
        cartItemMapper.deleteBatchIds(cartIds);
        
        // 清除 Redis 缓存中的商品
        try {
            String cartKey = CART_PREFIX + "user:" + userId;
            Object[] productIds = cartItems.stream()
                .map(item -> String.valueOf(item.getProductId()))
                .toArray();
            cartRedisTemplate.boundHashOps(cartKey).delete(productIds);
        } catch (Exception e) {
            log.error("清除Redis购物车缓存失败: userId={}", userId, e);
        }

        return order.getId();
    }

    @Override
    public OrderListVO getDetail(Long userId, Long orderId) {
        // 1. 获取订单
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        
        // 校验订单归属
        if (!order.getUserId().equals(userId)) {
            throw new RuntimeException("无权查看该订单");
        }
        
        // 2. 获取订单项
        QueryWrapper<OrderItem> itemQuery = new QueryWrapper<>();
        itemQuery.eq("order_id", orderId);
        List<OrderItem> orderItems = orderItemMapper.selectList(itemQuery);
        
        // 3. 获取店铺信息
        Shop shop = shopMapper.selectById(order.getShopId());
        
        // 4. 组装数据
        OrderListVO vo = new OrderListVO();
        BeanUtils.copyProperties(order, vo);
        if (shop != null) {
            vo.setShopName(shop.getName());
        }
        
        // 状态描述
        for (OrderState s : OrderState.values()) {
            if (s.getCode().equals(order.getStatus())) {
                vo.setStatusDesc(s.getDesc());
                break;
            }
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
        // 1. 查询订单主表
        QueryWrapper<Order> orderQuery = new QueryWrapper<>();
        orderQuery.eq("user_id", userId);
        if (status != null) {
            orderQuery.eq("status", status);
        }
        orderQuery.orderByDesc("create_time");
        List<Order> orders = orderMapper.selectList(orderQuery);

        if (orders == null || orders.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 批量查询订单项
        List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
        QueryWrapper<OrderItem> itemQuery = new QueryWrapper<>();
        itemQuery.in("order_id", orderIds);
        List<OrderItem> orderItems = orderItemMapper.selectList(itemQuery);
        
        // 3. 批量查询店铺信息
        List<Long> shopIds = orders.stream().map(Order::getShopId).distinct().collect(Collectors.toList());
        List<Shop> shops = new ArrayList<>();
        if (!shopIds.isEmpty()) {
            shops = shopMapper.selectBatchIds(shopIds);
        }
        Map<Long, String> shopMap = shops.stream().collect(Collectors.toMap(Shop::getId, Shop::getName));

        // 4. 组装数据
        Map<Long, List<OrderItem>> orderItemMap = orderItems.stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));

        return orders.stream().map(order -> {
            OrderListVO vo = new OrderListVO();
            BeanUtils.copyProperties(order, vo);
            vo.setShopName(shopMap.getOrDefault(order.getShopId(), "未知店铺"));
            
            // 状态描述
            for (OrderState s : OrderState.values()) {
                if (s.getCode().equals(order.getStatus())) {
                    vo.setStatusDesc(s.getDesc());
                    break;
                }
            }

            List<OrderItem> items = orderItemMap.get(order.getId());
            if (items != null) {
                List<OrderItemVO> itemVOs = items.stream().map(item -> {
                    OrderItemVO itemVO = new OrderItemVO();
                    BeanUtils.copyProperties(item, itemVO);
                    return itemVO;
                }).collect(Collectors.toList());
                vo.setItems(itemVOs);
            } else {
                vo.setItems(new ArrayList<>());
            }
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 生成订单号：时间戳 + 随机数
     */
    private String generateOrderSn() {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        int random = (int) (Math.random() * 9000 + 1000);
        return dateStr + random;
    }
}
