package com.aiolos.plaza.order.service;

import com.aiolos.plaza.order.model.bo.SeckillSubmitReq;

public interface PlazaSeckillService {
    
    /**
     * 提交秒杀请求
     * @param req 秒杀请求参数
     * @param userId 用户ID
     * @return 是否成功进入排队
     */
    boolean submitSeckill(SeckillSubmitReq req, Long userId);

    /**
     * 添加秒杀活动商品
     * @param req 添加参数
     * @return 活动ID
     */
    Long addSeckillActivity(com.aiolos.plaza.order.model.bo.SeckillActivityAddReq req);

    /**
     * 开启秒杀活动(预热)
     * @param activityId 活动ID
     */
    void startSeckillActivity(Long activityId);
}
