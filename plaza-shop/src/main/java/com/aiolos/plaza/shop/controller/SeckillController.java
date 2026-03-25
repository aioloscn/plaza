package com.aiolos.plaza.shop.controller;

import com.aiolos.plaza.bo.SeckillActivityAddReq;
import com.aiolos.plaza.shop.model.vo.SeckillProductVO;
import com.aiolos.plaza.shop.service.ShopSeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/seckill")
@Tag(name = "SeckillController", description = "秒杀模块接口")
public class SeckillController {
    
    @Autowired
    private ShopSeckillService shopSeckillService;

    @Operation(summary = "添加秒杀活动商品", description = "创建秒杀活动，设置活动商品、价格、库存和时间")
    @PostMapping("/add")
    public Long addSeckillActivity(@RequestBody SeckillActivityAddReq req) {
        log.info("接收到添加秒杀活动请求: req={}", req);
        return shopSeckillService.addSeckillActivity(req);
    }

    @Operation(summary = "开启秒杀活动(预热)", description = "预热秒杀库存和价格到Redis中")
    @PostMapping("/start/{activityId}")
    public void startSeckillActivity(@Parameter(description = "活动ID") @PathVariable("activityId") Long activityId) {
        log.info("接收到开启秒杀活动请求: activityId={}", activityId);
        shopSeckillService.startSeckillActivity(activityId);
    }

    @PostMapping("/get-seckill-activity")
    @Operation(summary = "获取商品秒杀活动")
    public List<SeckillProductVO> getSeckillActivity(@RequestParam("shopId") Long shopId) {
        return shopSeckillService.getSeckillActivity(shopId);
    }
}
