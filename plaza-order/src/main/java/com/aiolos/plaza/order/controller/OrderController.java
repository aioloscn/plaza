package com.aiolos.plaza.order.controller;

import com.aiolos.common.cloud.annotation.IgnoreAuth;
import com.aiolos.plaza.enums.PayType;
import com.aiolos.common.model.ContextInfo;
import com.aiolos.common.enums.error.ErrorEnum;
import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.order.model.bo.OrderSubmitReq;
import com.aiolos.plaza.order.service.PlazaOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import com.aiolos.plaza.order.model.vo.OrderListVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/order")
public class OrderController {

    @Autowired
    private PlazaOrderService plazaOrderService;

    /**
     * 提交订单
     */
    @PostMapping("/submit")
    public String submit(@RequestBody OrderSubmitReq req) {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        return plazaOrderService.submit(userId, req);
    }
    
    /**
     * 发起支付
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
        return plazaOrderService.pay(userId, orderSn, payType, isMobile);
    }

    /**
     * 支付回调 (支付宝)
     * 注意：这里不能校验登录态，因为是支付宝服务器调用的
     */
    @PostMapping("/pay/notify")
    @IgnoreAuth
    public String payNotify(@RequestParam java.util.Map<String, String> params) {
        return plazaOrderService.payNotify(params);
    }

    /**
     * 根据支付单号获取订单信息
     */
    @GetMapping("/payInfo")
    public OrderListVO getPayInfo(@RequestParam String paySn) {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        return plazaOrderService.getPayInfo(userId, paySn);
    }

    /**
     * 订单详情
     */
    @GetMapping("/{id}")
    public OrderListVO detail(@PathVariable Long id) {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        return plazaOrderService.getDetail(userId, id);
    }

    /**
     * 订单列表
     */
    @GetMapping("/list")
    public List<OrderListVO> list(@RequestParam(required = false) Integer status) {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        return plazaOrderService.list(userId, status);
    }
}
