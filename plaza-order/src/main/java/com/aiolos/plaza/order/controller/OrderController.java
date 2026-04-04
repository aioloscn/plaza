package com.aiolos.plaza.order.controller;

import com.aiolos.common.model.ContextInfo;
import com.aiolos.common.enums.error.ErrorEnum;
import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.order.model.bo.OrderSubmitReq;
import com.aiolos.plaza.order.model.vo.OrderConfirmVO;
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

    @PostMapping("/confirm")
    public OrderConfirmVO confirm(@RequestBody OrderSubmitReq req) {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        return plazaOrderService.confirm(userId, req);
    }

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
