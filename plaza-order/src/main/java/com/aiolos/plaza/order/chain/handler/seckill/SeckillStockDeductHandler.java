package com.aiolos.plaza.order.chain.handler.seckill;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.exceptions.SeckillExceptionEnum;
import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.SeckillOrderContext;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;

@Component
public class SeckillStockDeductHandler implements ChainHandler<SeckillOrderContext> {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private DefaultRedisScript<Long> seckillScript;

    @PostConstruct
    public void init() {
        seckillScript = new DefaultRedisScript<>();
        seckillScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckill_deduct.lua")));
        seckillScript.setResultType(Long.class);
    }

    @Override
    public void handle(SeckillOrderContext context, Chain<SeckillOrderContext> chain) {
        Long activityId = context.getReq().getActivityId();
        Long userId = context.getUserId();

        String stockKey = RedisKeyEnum.SECKILL_STOCK.getKey(activityId);
        String boughtKey = RedisKeyEnum.SECKILL_BOUGHT_USERS.getKey(activityId);

        Long result = stringRedisTemplate.execute(
                seckillScript,
                Arrays.asList(stockKey, boughtKey),
                "1",
                String.valueOf(userId)
        );

        if (result == null) {
            ExceptionUtil.throwException(SeckillExceptionEnum.SECKILL_ACTIVITY_ERROR);
        }

        if (result == -1L) {
            ExceptionUtil.throwException(SeckillExceptionEnum.SECKILL_NOT_START);
        } else if (result == -2L) {
            ExceptionUtil.throwException(SeckillExceptionEnum.SECKILL_REPEAT_BUY);
        } else if (result == -3L) {
            ExceptionUtil.throwException(SeckillExceptionEnum.SECKILL_SOLD_OUT);
        } else if (result == 1L) {
            String priceKey = RedisKeyEnum.SECKILL_PRICE.getKey(activityId);
            String priceStr = stringRedisTemplate.opsForValue().get(priceKey);
            if (priceStr == null) {
                ExceptionUtil.throwException(SeckillExceptionEnum.SECKILL_DATA_ERROR);
            }
            context.setSeckillPrice(new BigDecimal(priceStr));
            
            // 扣减成功，继续执行后续节点
            chain.proceed(context);
        }
    }
}