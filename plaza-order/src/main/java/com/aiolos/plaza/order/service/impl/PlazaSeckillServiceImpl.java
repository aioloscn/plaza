package com.aiolos.plaza.order.service.impl;

import com.aiolos.plaza.order.chain.ChainExecutor;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.SeckillOrderContext;
import com.aiolos.plaza.order.chain.handler.seckill.SeckillFreqLimitHandler;
import com.aiolos.plaza.order.chain.handler.seckill.SeckillMessageSendHandler;
import com.aiolos.plaza.order.chain.handler.seckill.SeckillStockDeductHandler;
import com.aiolos.plaza.order.model.bo.SeckillActivityAddReq;
import com.aiolos.plaza.order.model.bo.SeckillSubmitReq;
import com.aiolos.plaza.order.service.PlazaSeckillService;
import com.aiolos.plaza.model.po.SeckillActivity;
import com.aiolos.plaza.service.SeckillActivityService;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.exceptions.SeckillExceptionEnum;
import org.springframework.data.redis.core.StringRedisTemplate;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class PlazaSeckillServiceImpl implements PlazaSeckillService {

    @Resource
    private ChainExecutor chainExecutor;

    @Resource
    private SeckillFreqLimitHandler seckillFreqLimitHandler;

    @Resource
    private SeckillStockDeductHandler seckillStockDeductHandler;

    @Resource
    private SeckillMessageSendHandler seckillMessageSendHandler;

    @Resource
    private SeckillActivityService seckillActivityService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean submitSeckill(SeckillSubmitReq req, Long userId) {
        SeckillActivity activity = seckillActivityService.getById(req.getActivityId());
        if (activity == null || activity.getStatus() != 1) {
            ExceptionUtil.throwException(SeckillExceptionEnum.SECKILL_ACTIVITY_ERROR);
        }
        
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime()) || now.isAfter(activity.getEndTime())) {
            ExceptionUtil.throwException(SeckillExceptionEnum.SECKILL_NOT_START);
        }

        SeckillOrderContext context = new SeckillOrderContext();
        context.setUserId(userId);
        context.setReq(req);
        context.setSuccess(false);

        List<ChainHandler<SeckillOrderContext>> handlers = Arrays.asList(
                seckillFreqLimitHandler,
                seckillStockDeductHandler,
                seckillMessageSendHandler
        );

        chainExecutor.execute(handlers, context);

        return context.isSuccess();
    }

    @Override
    public Long addSeckillActivity(SeckillActivityAddReq req) {
        SeckillActivity activity = new SeckillActivity();
        activity.setShopId(req.getShopId());
        activity.setProductId(req.getProductId());
        activity.setPrice(req.getPrice());
        activity.setStock(req.getStock());
        activity.setStartTime(req.getStartTime());
        activity.setEndTime(req.getEndTime());
        activity.setStatus(0); // 0:未开始

        seckillActivityService.save(activity);
        log.info("添加秒杀活动成功，活动ID: {}", activity.getId());
        return activity.getId();
    }

    @Override
    public void startSeckillActivity(Long activityId) {
        SeckillActivity activity = seckillActivityService.getById(activityId);
        if (activity == null) {
            ExceptionUtil.throwException(SeckillExceptionEnum.SECKILL_ACTIVITY_ERROR);
        }

        // 将库存和价格预热到 Redis
        String stockKey = RedisKeyEnum.SECKILL_STOCK.getKey(activityId);
        String priceKey = RedisKeyEnum.SECKILL_PRICE.getKey(activityId);

        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(activity.getStock()));
        stringRedisTemplate.opsForValue().set(priceKey, activity.getPrice().toString());

        // 更新状态为 1:进行中
        activity.setStatus(1);
        seckillActivityService.updateById(activity);
        
        log.info("开启秒杀活动成功，活动ID: {}, 预热库存: {}, 预热价格: {}", activityId, activity.getStock(), activity.getPrice());
    }
}
