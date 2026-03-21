package com.aiolos.plaza.enums.exceptions;

import com.aiolos.common.enums.error.CommonError;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SeckillExceptionEnum implements CommonError {

    SECKILL_IN_QUEUE(2001, "抢购排队中，请稍后查询结果"),
    SECKILL_FREQ_LIMIT(2002, "您的请求过于频繁，请稍后再试"),
    SECKILL_ACTIVITY_ERROR(2003, "秒杀活动异常"),
    SECKILL_NOT_START(2004, "秒杀活动尚未开始或已结束"),
    SECKILL_REPEAT_BUY(2005, "您已经参与过该活动，不能重复抢购"),
    SECKILL_SOLD_OUT(2006, "手慢了，商品已被抢光"),
    SECKILL_DATA_ERROR(2007, "秒杀活动数据异常，请稍后重试"),
    SECKILL_FAIL(2008, "抢购失败，活动异常或库存不足"),
    ;

    private final Integer errCode;
    private final String errMsg;

    @Override
    public CommonError setErrMsg(String s) {
        return null;
    }
}
