package com.aiolos.plaza.order.mq.producer;

import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.mapper.ProductMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.model.po.Product;
import com.aiolos.plaza.mq.message.StockDeductMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;

@Slf4j
@Component
@RocketMQTransactionListener(rocketMQTemplateBeanName = "seckillTxRocketMQTemplate")
public class SeckillOrderTransactionListener implements RocketMQLocalTransactionListener {

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private OrderItemMapper orderItemMapper;

    @Resource
    private ParentOrderMapper parentOrderMapper;

    @Resource
    private ProductMapper productMapper;

    @Resource
    private OrderMessageProducer orderMessageProducer;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private DefaultRedisScript<Long> seckillRollbackScript;

    @PostConstruct
    public void init() {
        seckillRollbackScript = new DefaultRedisScript<>();
        seckillRollbackScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckill_rollback.lua")));
        seckillRollbackScript.setResultType(Long.class);
    }

    /**
     * 本地事务执行入口（由 sendMessageInTransaction 自动回调）：
     * 1) 幂等检查（按 orderSn 查单）
     * 2) 创建父单/子单/订单项
     * 3) 发送超时关单消息
     * 4) 成功返回 COMMIT，失败返回 ROLLBACK 并补偿 Redis 预扣
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        if (!(arg instanceof SeckillOrderTxContext txContext)) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            Long exists = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                    .eq(Order::getOrderSn, txContext.getOrderSn()));
            if (exists != null && exists > 0) {
                return RocketMQLocalTransactionState.COMMIT;
            }
            Product product = productMapper.selectById(txContext.getProductId());
            if (product == null) {
                compensateRedis(txContext.getActivityId(), txContext.getUserId(), txContext.getCount());
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            BigDecimal totalAmount = txContext.getPrice().multiply(new BigDecimal(txContext.getCount()));
            LocalDateTime now = LocalDateTime.now();
            ParentOrder parentOrder = new ParentOrder();
            parentOrder.setParentOrderSn(txContext.getParentOrderSn());
            parentOrder.setUserId(txContext.getUserId());
            parentOrder.setTotalAmount(totalAmount);
            parentOrder.setPayAmount(totalAmount);
            parentOrder.setStatus(OrderState.CREATED.getCode());
            parentOrder.setPayType(1);
            parentOrder.setDeleteStatus(0);
            parentOrder.setCreateTime(now);
            parentOrder.setUpdateTime(now);
            parentOrderMapper.insert(parentOrder);
            Order order = new Order();
            order.setOrderSn(txContext.getOrderSn());
            order.setParentOrderSn(txContext.getParentOrderSn());
            order.setUserId(txContext.getUserId());
            order.setShopId(txContext.getShopId());
            order.setTotalAmount(totalAmount);
            order.setPayAmount(totalAmount);
            order.setFreightAmount(BigDecimal.ZERO);
            order.setPromotionAmount(BigDecimal.ZERO);
            order.setPayType(1);
            order.setStatus(OrderState.CREATED.getCode());
            order.setDeleteStatus(0);
            order.setCreateTime(now);
            order.setUpdateTime(now);
            order.setConfirmStatus(0);
            order.setReceiverName("秒杀用户");
            orderMapper.insert(order);
            OrderItem orderItem = new OrderItem();
            orderItem.setOrderId(order.getId());
            orderItem.setOrderSn(txContext.getOrderSn());
            orderItem.setProductId(product.getId());
            orderItem.setProductPic(product.getImageUrl());
            orderItem.setProductName(product.getName());
            orderItem.setProductPrice(txContext.getPrice());
            orderItem.setProductQuantity(txContext.getCount());
            orderItem.setRealAmount(totalAmount);
            orderItemMapper.insert(orderItem);
            orderMessageProducer.sendOrderTimeoutMessage(order.getId(), 14);
            log.info("秒杀本地事务下单成功: orderSn={}", txContext.getOrderSn());
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("秒杀本地事务下单失败", e);
            compensateRedis(txContext.getActivityId(), txContext.getUserId(), txContext.getCount());
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * 事务半消息长时间处于未知状态时，RocketMQ Broker会自动触发该方法检查事务状态
     * @param message 半消息
     * @return 事务状态
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        Object payload = message.getPayload();
        if (!(payload instanceof StockDeductMessage stockDeductMessage)) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        Long exists = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderSn, stockDeductMessage.orderSn()));
        if (exists != null && exists > 0) {
            return RocketMQLocalTransactionState.COMMIT;
        }
        Object activityIdHeader = message.getHeaders().get("activityId");
        Object userIdHeader = message.getHeaders().get("userId");
        Object countHeader = message.getHeaders().get("count");
        if (activityIdHeader != null && userIdHeader != null) {
            compensateRedis(Long.valueOf(activityIdHeader.toString()), Long.valueOf(userIdHeader.toString()),
                    countHeader == null ? 1 : Integer.valueOf(countHeader.toString()));
        }
        return RocketMQLocalTransactionState.ROLLBACK;
    }

    /**
     * Redis 预扣补偿：
     * - 从活动已购集合移除用户
     * - 若移除成功，库存加回
     */
    private void compensateRedis(Long activityId, Long userId, Integer count) {
        if (activityId == null || userId == null || count == null || count <= 0) {
            return;
        }
        String stockKey = RedisKeyEnum.SECKILL_STOCK.getKey(activityId);
        // 存在说明用户参与过秒杀，需要回滚库存
        String boughtKey = RedisKeyEnum.SECKILL_BOUGHT_USERS.getKey(activityId);
        Long result = stringRedisTemplate.execute(seckillRollbackScript,
                Arrays.asList(stockKey, boughtKey),
                String.valueOf(count), String.valueOf(userId));
        log.info("秒杀Redis补偿完成: activityId={}, userId={}, count={}, result={}", activityId, userId, count, result);
    }
}
