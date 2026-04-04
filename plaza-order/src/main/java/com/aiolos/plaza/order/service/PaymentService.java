package com.aiolos.plaza.order.service;

import java.util.Map;

public interface PaymentService {

    /**
     * 发起支付，返回支付表单 HTML。
     */
    String pay(Long userId, String orderSn, Integer payType, boolean isMobile);

    /**
     * 支付回调处理。
     */
    String payNotify(Map<String, String> params);
}
