package com.aiolos.plaza.shop.service;

import com.aiolos.plaza.bo.SeckillActivityAddReq;
import com.aiolos.plaza.shop.model.vo.SeckillProductVO;

import java.util.List;

public interface ShopSeckillService {

    /**
     * 添加秒杀活动商品
     * @param req 添加参数
     * @return 活动ID
     */
    Long addSeckillActivity(SeckillActivityAddReq req);

    /**
     * 开启秒杀活动(预热)
     * @param activityId 活动ID
     */
    void startSeckillActivity(Long activityId);

    /**
     * 获取秒杀活动商品
     * @param shopId
     * @return
     */
    List<SeckillProductVO> getSeckillActivity(Long shopId);
}
