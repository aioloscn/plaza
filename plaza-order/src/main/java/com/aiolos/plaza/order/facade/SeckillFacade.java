package com.aiolos.plaza.order.facade;

import com.aiolos.plaza.order.chain.ChainExecutor;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.SeckillOrderContext;
import com.aiolos.plaza.order.chain.handler.seckill.SeckillFreqLimitHandler;
import com.aiolos.plaza.order.chain.handler.seckill.SeckillMessageSendHandler;
import com.aiolos.plaza.order.chain.handler.seckill.SeckillStockDeductHandler;
import com.aiolos.plaza.order.model.bo.SeckillSubmitReq;
import com.aiolos.plaza.order.api.PlazaSeckillService;
import com.aiolos.plaza.model.po.SeckillActivity;
import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.exceptions.SeckillExceptionEnum;
import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class SeckillFacade implements PlazaSeckillService {

    @Resource
    private ChainExecutor chainExecutor;

    @Resource
    private SeckillFreqLimitHandler seckillFreqLimitHandler;

    @Resource
    private SeckillStockDeductHandler seckillStockDeductHandler;

    @Resource
    private SeckillMessageSendHandler seckillMessageSendHandler;

    @Resource
    @Qualifier("shopRedisTemplate")
    private StringRedisTemplate shopRedisTemplate;

    @Override
    public boolean submitSeckill(SeckillSubmitReq req, Long userId) {
        String infoKey = RedisKeyEnum.SECKILL_ACTIVITY_INFO.getKey(req.getActivityId());
        String activityJson = shopRedisTemplate.opsForValue().get(infoKey);

        if (StringUtils.isBlank(activityJson)) {
            // 如果 Redis 没有，说明活动没开启或不存在，直接返回失败，阻断 DB 访问
            ExceptionUtil.throwException(SeckillExceptionEnum.SECKILL_ACTIVITY_ERROR);
        }
        
        SeckillActivity activity = JSON.parseObject(activityJson, SeckillActivity.class);
        
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

}
