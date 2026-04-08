package com.aiolos.plaza.order.application.seckill;

import com.aiolos.plaza.order.model.bo.SeckillSubmitReq;

public interface SeckillService {
    
    /**
     * 提交秒杀请求
     * @param req 秒杀请求参数
     * @param userId 用户ID
     * @return 是否成功进入排队
     */
    boolean submitSeckill(SeckillSubmitReq req, Long userId);

}
