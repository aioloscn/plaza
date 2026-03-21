package com.aiolos.plaza.order.statemachine.action;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.enums.OrderEvent;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ProductMapper;
import com.aiolos.plaza.mapper.ProductStockLogMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ProductStockLog;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单取消时的库存归还动作
 */
@Slf4j
@Component
public class OrderStockReleaseAction implements Action<OrderState, OrderEvent> {

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductStockLogMapper productStockLogMapper;

    @Autowired
    @Qualifier("shopRedisTemplate")
    private StringRedisTemplate shopRedisTemplate;

    @Override
    public void execute(StateContext<OrderState, OrderEvent> context) {
        Long orderId = (Long) context.getMessageHeader("orderId");
        if (orderId == null) {
            String errorMsg = "归还库存失败：未找到订单ID";
            log.error(errorMsg);
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }

        // 记录已经成功增加的 Redis 库存，用于异常时补偿回滚
        List<OrderItem> redisIncrementedItems = new ArrayList<>();

        try {
            Order order = orderMapper.selectById(orderId);
            String orderSn = order != null ? order.getOrderSn() : null;

            // 归还库存 (数据库与Redis缓存)
            QueryWrapper<OrderItem> itemQuery = new QueryWrapper<>();
            itemQuery.eq("order_id", orderId);
            List<OrderItem> orderItems = orderItemMapper.selectList(itemQuery);
            if (orderItems.isEmpty()) {
                log.error("归还库存失败：订单 {} 不存在商品项", orderId);
            }
            
            for (OrderItem item : orderItems) {
                if (item.getProductId() != null && item.getProductQuantity() != null) {
                    productMapper.addStock(item.getProductId(), item.getProductQuantity());
                    // 恢复 Redis 缓存库存
                    shopRedisTemplate.opsForValue().increment(RedisKeyEnum.PRODUCT_STOCK.getKey(item.getProductId()), item.getProductQuantity());
                    // 记录成功操作 Redis 的商品
                    redisIncrementedItems.add(item);
                    
                    // 记录库存操作日志
                    ProductStockLog stockLog = new ProductStockLog();
                    stockLog.setProductId(item.getProductId());
                    stockLog.setOrderSn(orderSn);
                    stockLog.setAmount(item.getProductQuantity()); // 正数表示增加/归还
                    stockLog.setType(2); // 2-取消回滚
                    stockLog.setCreateTime(LocalDateTime.now());
                    productStockLogMapper.insert(stockLog);
                }
            }
            log.info("订单取消，库存归还成功，订单ID: {}", orderId);
        } catch (Exception e) {
            log.error("订单取消归还库存异常，订单ID: {}", orderId, e);

            // 1. 补偿：将已经成功增加的 Redis 库存再减回去，防止 Redis 和 MySQL 数据不一致
            for (OrderItem item : redisIncrementedItems) {
                try {
                    shopRedisTemplate.opsForValue().decrement(RedisKeyEnum.PRODUCT_STOCK.getKey(item.getProductId()), item.getProductQuantity());
                    log.info("异常补偿：已扣除 Redis 库存，商品ID: {}, 数量: {}", item.getProductId(), item.getProductQuantity());
                } catch (Exception redisEx) {
                    log.error("异常补偿：扣除 Redis 库存失败，可能存在脏数据！商品ID: {}", item.getProductId(), redisEx);
                }
            }

            // 2. 手动标记当前 Spring 事务为回滚状态，状态机拦截器里的事务也会回滚
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            
            // 3. 必须抛出异常，以便外层感知并让状态机记录 Error
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
        }
    }
}