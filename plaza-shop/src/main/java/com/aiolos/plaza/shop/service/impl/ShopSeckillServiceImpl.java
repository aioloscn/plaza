package com.aiolos.plaza.shop.service.impl;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.bo.SeckillActivityAddReq;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.exceptions.SeckillExceptionEnum;
import com.aiolos.plaza.model.po.SeckillActivity;
import com.aiolos.plaza.service.SeckillActivityService;
import com.aiolos.plaza.shop.model.vo.SeckillProductVO;
import com.aiolos.plaza.shop.model.vo.ProductVO;
import com.aiolos.plaza.shop.service.ShopProductService;
import com.aiolos.plaza.shop.service.ShopSeckillService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class ShopSeckillServiceImpl implements ShopSeckillService {

    private final SeckillActivityService seckillActivityService;
    private final StringRedisTemplate redisTemplate;
    private final ShopProductService shopProductService;
    
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
        
        // 更新状态为 1:进行中
        activity.setStatus(1);

        // 将库存和价格预热到 Redis
        String stockKey = RedisKeyEnum.SECKILL_STOCK.getKey(activityId);
        String priceKey = RedisKeyEnum.SECKILL_PRICE.getKey(activityId);

        redisTemplate.opsForValue().set(stockKey, String.valueOf(activity.getStock()));
        redisTemplate.opsForValue().set(priceKey, activity.getPrice().toString());

        // 预热整个活动元信息
        String infoKey = RedisKeyEnum.SECKILL_ACTIVITY_INFO.getKey(activityId);
        redisTemplate.opsForValue().set(infoKey, JSON.toJSONString(activity));

        // 删除对应的店铺活动列表缓存，让下一次查询重新加载并缓存
        String shopListKey = RedisKeyEnum.SECKILL_SHOP_LIST.getKey(activity.getShopId());
        redisTemplate.delete(shopListKey);

        seckillActivityService.updateById(activity);

        log.info("开启秒杀活动成功，活动ID: {}, 预热库存: {}, 预热价格: {}", activityId, activity.getStock(), activity.getPrice());
    }

    @Override
    public List<SeckillProductVO> getSeckillActivity(Long shopId) {
        String shopListKey = RedisKeyEnum.SECKILL_SHOP_LIST.getKey(shopId);
        String listJson = redisTemplate.opsForValue().get(shopListKey);
        
        LocalDateTime now = LocalDateTime.now();
        List<SeckillProductVO> resultList = null;
        
        if (StringUtils.isNotBlank(listJson) && !StringUtils.equals(listJson, "[]")) {
            List<SeckillProductVO> cachedList = JSONArray.parseArray(listJson, SeckillProductVO.class);
            if (cachedList != null && !cachedList.isEmpty()) {
                // 从缓存中过滤出当前时间在活动期间内的商品
                resultList = cachedList.stream()
                        .filter(vo -> vo.getStartTime() != null && vo.getEndTime() != null 
                                && !now.isBefore(vo.getStartTime()) 
                                && !now.isAfter(vo.getEndTime()))
                        .collect(Collectors.toList());
            }
        } else if (StringUtils.equals(listJson, "[]")) {
            return new ArrayList<>();
        }
        
        if (resultList == null) {
            // 1. 查询当前店铺进行中的秒杀活动，且活动未结束
            List<SeckillActivity> list = seckillActivityService.lambdaQuery()
                    .eq(SeckillActivity::getShopId, shopId)
                    .eq(SeckillActivity::getStatus, 1) // 确保是进行中的状态
                    .le(SeckillActivity::getStartTime, now)
                    .ge(SeckillActivity::getEndTime, now)   // 活动未结束
                    .list();
                    
            // 2. 如果没有秒杀活动，设置空缓存防止穿透
            if (list == null || list.isEmpty()) {
                redisTemplate.opsForValue().set(shopListKey, "[]", 60L, TimeUnit.SECONDS);
                return new ArrayList<>();
            }
            
            // 3. 将秒杀活动映射为 SeckillProductVO 返回，因为前端可能把这个当做特殊商品展示
            resultList = list.stream().map(activity -> {
                SeckillProductVO vo = new SeckillProductVO();
                vo.setId(activity.getProductId()); // 真实商品ID
                vo.setActivityId(activity.getId()); // 秒杀活动ID
                vo.setShopId(activity.getShopId());
                vo.setPrice(activity.getPrice());
                vo.setStock(activity.getStock());
                vo.setStatus(activity.getStatus());
                vo.setStartTime(activity.getStartTime());
                vo.setEndTime(activity.getEndTime());
                
                // 联查商品表，获取真实的商品图片和名称
                ProductVO originProduct = shopProductService.getById(activity.getProductId());
                if (originProduct != null) {
                    vo.setName(originProduct.getName());
                    vo.setImageUrl(originProduct.getImageUrl());
                    vo.setDescription(originProduct.getDescription());
                    vo.setOriginalPrice(originProduct.getPrice());
                } else {
                    vo.setName("秒杀商品 (活动ID:" + activity.getId() + ")");
                }
                
                return vo;
            }).collect(Collectors.toList());
            
            redisTemplate.opsForValue().set(shopListKey, JSON.toJSONString(resultList), RedisKeyEnum.SECKILL_SHOP_LIST.getDefaultExpireSeconds(), TimeUnit.SECONDS);
        }

        // 4. 实时查询 Redis 获取最新库存
        if (resultList != null && !resultList.isEmpty()) {
            resultList.forEach(vo -> {
                String stockKey = RedisKeyEnum.SECKILL_STOCK.getKey(vo.getActivityId());
                String stockStr = redisTemplate.opsForValue().get(stockKey);
                if (StringUtils.isNotBlank(stockStr)) {
                    vo.setStock(Integer.parseInt(stockStr));
                }
            });
        }

        return resultList != null ? resultList : new ArrayList<>();
    }
}
