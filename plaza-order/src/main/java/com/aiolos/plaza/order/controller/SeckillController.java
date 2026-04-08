package com.aiolos.plaza.order.controller;

import com.aiolos.common.enums.error.ErrorEnum;
import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.common.model.ContextInfo;
import com.aiolos.plaza.enums.exceptions.SeckillExceptionEnum;
import com.aiolos.plaza.order.model.bo.SeckillSubmitReq;
import com.aiolos.plaza.order.application.seckill.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/seckill")
@Tag(name = "SeckillController", description = "秒杀模块接口")
public class SeckillController {

    @Resource
    private SeckillService seckillService;

    @Operation(summary = "提交秒杀请求", description = "网关限流后到达此接口，进行Lua库存扣减并发送MQ异步下单")
    @PostMapping("/submit")
    public String submitSeckill(@RequestBody SeckillSubmitReq req) {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        
        log.info("接收到秒杀请求: userId={}, req={}", userId, req);
        // 如果内部抛出业务异常，会被全局异常处理器捕获，并直接返回给前端对应信息
        boolean success = seckillService.submitSeckill(req, userId);
        if (success) {
            return "抢购排队中，请稍后查询结果";
        } else {
            ExceptionUtil.throwException(SeckillExceptionEnum.SECKILL_FAIL);
        }
        return null;
    }
}
