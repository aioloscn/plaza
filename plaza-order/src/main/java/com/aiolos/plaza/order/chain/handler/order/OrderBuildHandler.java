package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.OrderType;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.mapper.ProductMapper;
import com.aiolos.plaza.model.po.Address;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.model.po.Product;
import com.aiolos.plaza.mq.message.StockDeductMessage;
import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class OrderBuildHandler implements ChainHandler<OrderCreateContext> {

    @Autowired
    @Qualifier("shopRedisTemplate")
    private StringRedisTemplate shopRedisTemplate;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private ParentOrderMapper parentOrderMapper;

    private DefaultRedisScript<Long> stockDeductScript;

    @PostConstruct
    public void init() {
        stockDeductScript = new DefaultRedisScript<>();
        stockDeductScript.setResultType(Long.class);
        stockDeductScript.setLocation(new ClassPathResource("lua/stock_deduct.lua"));
    }

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        String parentOrderSn = generateOrderSn("P");
        context.setParentOrderSn(parentOrderSn);
        
        Address address = context.getAddress();
        var req = context.getReq();

        for (Map.Entry<Long, List<CartItem>> entry : context.getShopCartMap().entrySet()) {
            Long shopId = entry.getKey();
            List<CartItem> shopItems = entry.getValue();

            String orderSn = generateOrderSn("D");
            BigDecimal totalAmount = BigDecimal.ZERO;

            for (CartItem item : shopItems) {
                Long productId = item.getProductId();
                context.getAllCartIds().add(item.getId());

                String productJson = shopRedisTemplate.opsForValue().get(RedisKeyEnum.PRODUCT_INFO.getKey(productId));
                if (productJson == null) {
                    Product dbProduct = productMapper.selectById(productId);
                    if (dbProduct == null || dbProduct.getStatus() != 1) {
                        ExceptionUtil.throwException(OrderExceptionEnum.PRODUCT_NOT_EXIST);
                    }
                    shopRedisTemplate.opsForValue().set(RedisKeyEnum.PRODUCT_INFO.getKey(productId), JSON.toJSONString(dbProduct), 1, TimeUnit.DAYS);
                    shopRedisTemplate.opsForValue().setIfAbsent(RedisKeyEnum.PRODUCT_STOCK.getKey(productId), String.valueOf(dbProduct.getStock()));
                    productJson = JSON.toJSONString(dbProduct);
                }

                Product product = JSON.parseObject(productJson, Product.class);
                if (product.getStatus() != 1) {
                    ExceptionUtil.throwException(OrderExceptionEnum.PRODUCT_NOT_EXIST);
                }

                Long result = shopRedisTemplate.execute(stockDeductScript,
                        Collections.singletonList(RedisKeyEnum.PRODUCT_STOCK.getKey(productId)),
                        String.valueOf(item.getQuantity()));

                if (result == -2) {
                    ExceptionUtil.throwException(OrderExceptionEnum.PRODUCT_NOT_EXIST);
                } else if (result == -1) {
                    ExceptionUtil.throwException(OrderExceptionEnum.STOCK_NOT_ENOUGH);
                }

                context.getStockDeductMessages().add(new StockDeductMessage(productId, item.getQuantity(), orderSn));

                BigDecimal price = product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO;
                item.setPriceSnapshot(price);
                BigDecimal itemAmount = price.multiply(new BigDecimal(item.getQuantity()));
                totalAmount = totalAmount.add(itemAmount);
            }

            Order order = new Order();
            order.setOrderSn(orderSn);
            order.setParentOrderSn(parentOrderSn);
            order.setUserId(context.getUserId());
            order.setShopId(shopId);
            order.setOrderType(OrderType.NORMAL.getCode());
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

            if (req.getShopNotes() != null && req.getShopNotes().containsKey(shopId)) {
                order.setNote(req.getShopNotes().get(shopId));
            }

            order.setDeleteStatus(0);
            order.setCreateTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());
            order.setConfirmStatus(0);

            orderMapper.insert(order);
            context.getOrderIds().add(order.getId());

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

            context.setParentTotalAmount(context.getParentTotalAmount().add(totalAmount));
        }

        ParentOrder parentOrder = new ParentOrder();
        parentOrder.setParentOrderSn(parentOrderSn);
        parentOrder.setUserId(context.getUserId());
        parentOrder.setTotalAmount(context.getParentTotalAmount());
        parentOrder.setPayAmount(context.getParentTotalAmount());
        parentOrder.setStatus(OrderState.CREATED.getCode());
        parentOrder.setPayType(req.getPayType());
        parentOrder.setOrderType(OrderType.NORMAL.getCode());
        parentOrder.setDeleteStatus(0);
        parentOrder.setCreateTime(LocalDateTime.now());
        parentOrder.setUpdateTime(LocalDateTime.now());
        parentOrderMapper.insert(parentOrder);
        
        // 构建完成，继续执行后续的后置处理节点
        chain.proceed(context);
    }

    private String generateOrderSn(String prefix) {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        int random = (int) (Math.random() * 9000 + 1000);
        return prefix + dateStr + random;
    }
}
