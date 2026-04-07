package com.aiolos.plaza.order.chain.handler.seckill;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.exceptions.SeckillExceptionEnum;
import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.SeckillOrderContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SeckillFreqLimitHandler implements ChainHandler<SeckillOrderContext> {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void handle(SeckillOrderContext context, Chain<SeckillOrderContext> chain) {
        Long userId = context.getUserId();
        String limitKey = RedisKeyEnum.SECKILL_LIMIT.getKey(userId);
        Boolean setLimit = stringRedisTemplate.opsForValue().setIfAbsent(limitKey, "1",
                java.time.Duration.ofSeconds(RedisKeyEnum.SECKILL_LIMIT.getDefaultExpireSeconds()));
        if (Boolean.FALSE.equals(setLimit)) {
            // 抛出自定义异常，交由全局异常处理器拦截，返回具体错误信息给前端
            ExceptionUtil.throwException(SeckillExceptionEnum.SECKILL_FREQ_LIMIT);
        }
        
        chain.proceed(context);
    }
}
