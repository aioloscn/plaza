package com.aiolos.plaza.order.api;

import java.util.Map;

public interface PaymentService {

    /**
     * 发起支付，返回支付表单 HTML
     */
    String pay(Long userId, String orderSn, Integer payType, boolean isMobile);

    /**
     * 支付回调处理
     */
    String payNotify(Map<String, String> params);

    /**
     * 退款回调处理
     */
    String refundNotify(Map<String, String> params);

    /**
     * 用户主动发起退款申请
     */
    String refund(Long userId, String parentOrderSn);

}
