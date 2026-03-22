package com.aiolos.plaza.order.controller;

import com.aiolos.common.enums.error.ErrorEnum;
import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.exceptions.SeckillExceptionEnum;
import com.aiolos.plaza.order.model.bo.SeckillActivityAddReq;
import com.aiolos.plaza.order.model.bo.SeckillSubmitReq;
import com.aiolos.plaza.order.service.PlazaSeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "SeckillController", description = "秒杀模块接口")
@RestController
@RequestMapping("/seckill")
public class SeckillController {

    @Resource
    private PlazaSeckillService seckillService;

    @Operation(summary = "提交秒杀请求", description = "网关限流后到达此接口，进行Lua库存扣减并发送MQ异步下单")
    @PostMapping("/submit")
    public String submitSeckill(@RequestBody SeckillSubmitReq req,
                                @RequestHeader(value = "userId", required = false) Long userId) {
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        
        log.info("接收到秒杀请求: userId={}, req={}", userId, req);
        // 如果内部抛出业务异常，会被全局异常处理器捕获，直接返回给前端对应信息
        boolean success = seckillService.submitSeckill(req, userId);
        if (success) {
            return "抢购排队中，请稍后查询结果";
        } else {
            ExceptionUtil.throwException(SeckillExceptionEnum.SECKILL_FAIL);
        }
        return null;
    }

    @Operation(summary = "添加秒杀活动商品", description = "创建秒杀活动，设置活动商品、价格、库存和时间")
    @PostMapping("/add")
    public Long addSeckillActivity(@RequestBody SeckillActivityAddReq req) {
        log.info("接收到添加秒杀活动请求: req={}", req);
        return seckillService.addSeckillActivity(req);
    }

    @Operation(summary = "开启秒杀活动(预热)", description = "预热秒杀库存和价格到Redis中")
    @PostMapping("/start/{activityId}")
    public String startSeckillActivity(@Parameter(description = "活动ID") @PathVariable("activityId") Long activityId) {
        log.info("接收到开启秒杀活动请求: activityId={}", activityId);
        seckillService.startSeckillActivity(activityId);
        return "秒杀活动开启成功";
    }
}
