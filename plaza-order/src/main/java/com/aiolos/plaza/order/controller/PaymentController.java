package com.aiolos.plaza.order.controller;

import com.aiolos.common.cloud.annotation.IgnoreAuth;
import com.aiolos.common.enums.error.ErrorEnum;
import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.common.model.ContextInfo;
import com.aiolos.plaza.enums.PayType;
import com.aiolos.plaza.order.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/payment")
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    /**
     * 发起支付。
     */
    @PostMapping("/pay")
    public String pay(@RequestParam String orderSn, @RequestParam(required = false) Integer payType, jakarta.servlet.http.HttpServletRequest request) {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        if (payType == null) {
            payType = PayType.ALIPAY.getCode();
        }
        String ua = request.getHeader("User-Agent");
        boolean isMobile = false;
        if (ua != null) {
            ua = ua.toLowerCase();
            isMobile = ua.contains("mobile") || ua.contains("android") || ua.contains("iphone");
        }
        return paymentService.pay(userId, orderSn, payType, isMobile);
    }

    /**
     * 支付回调（支付宝服务器调用，不校验登录态）。
     */
    @PostMapping("/pay/notify")
    @IgnoreAuth
    public String payNotify(@RequestParam Map<String, String> params) {
        return paymentService.payNotify(params);
    }
}
