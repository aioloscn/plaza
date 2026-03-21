package com.aiolos.plaza.order.model.bo;

import lombok.Data;

@Data
public class SeckillSubmitReq {
    private Long activityId;
    private Long shopId;
    private Long productId;
    // 购买数量默认1
}
