package com.aiolos.plaza.order.controller;

import com.aiolos.common.enums.error.ErrorEnum;
import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.exceptions.SeckillExceptionEnum;
import com.aiolos.plaza.order.model.bo.SeckillSubmitReq;
import com.aiolos.plaza.order.service.PlazaSeckillService;
import io.swagger.v3.oas.annotations.Operation;
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
}
