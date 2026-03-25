package com.aiolos.plaza.order.service.impl;

import com.aiolos.plaza.mapper.ProductStockLogMapper;
import com.aiolos.plaza.model.po.ProductStockLog;
import com.aiolos.plaza.model.po.SeckillActivity;
import com.aiolos.plaza.mq.message.SeckillStockDeductMessage;
import com.aiolos.plaza.order.service.SeckillStockDeductService;
import com.aiolos.plaza.service.SeckillActivityService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
public class SeckillStockDeductServiceImpl implements SeckillStockDeductService {

    @Resource
    private SeckillActivityService seckillActivityService;

    @Resource
    private ProductStockLogMapper productStockLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void consume(SeckillStockDeductMessage message) {
        if (message == null || message.activityId() == null || message.quantity() == null) {
            return;
        }
        if (StringUtils.isNotBlank(message.orderSn())) {
            Long exists = productStockLogMapper.selectCount(new LambdaQueryWrapper<ProductStockLog>()
                    .eq(ProductStockLog::getOrderSn, message.orderSn())
                    .eq(ProductStockLog::getType, 1));
            if (exists != null && exists > 0) {
                log.warn("秒杀库存扣减消息重复消费, orderSn: {}", message.orderSn());
                return;
            }
        }
        boolean success = seckillActivityService.lambdaUpdate()
                .eq(SeckillActivity::getId, message.activityId())
                .ge(SeckillActivity::getStock, message.quantity())
                .setSql("stock = stock - " + message.quantity())
                .update();
        if (success) {
            log.info("秒杀数据库库存扣减成功, activityId: {}, quantity: {}", message.activityId(), message.quantity());
            ProductStockLog stockLog = new ProductStockLog();
            stockLog.setProductId(message.productId());
            stockLog.setOrderSn(message.orderSn());
            stockLog.setAmount(-message.quantity());
            stockLog.setType(1);
            stockLog.setCreateTime(LocalDateTime.now());
            productStockLogMapper.insert(stockLog);
            return;
        }
        log.warn("秒杀数据库库存扣减失败, message: {}", message);
    }
}
