package com.aiolos.plaza.order.controller;

import com.aiolos.common.model.ContextInfo;
import com.aiolos.common.enums.error.ErrorEnum;
import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.order.model.bo.OrderSubmitReq;
import com.aiolos.plaza.order.model.vo.OrderConfirmVO;
import com.aiolos.plaza.order.application.order.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import com.aiolos.plaza.order.model.vo.OrderListVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping("/confirm")
    public OrderConfirmVO confirm(@RequestBody OrderSubmitReq req) {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        return orderService.confirm(userId, req);
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
        return orderService.submit(userId, req);
    }
    
    /**
     * 根据支付单号获取订单信息
     */
    @GetMapping("/payInfo")
    public OrderListVO getPayInfo(@RequestParam("paySn") String paySn) {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        return orderService.getPayInfo(userId, paySn);
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
        return orderService.getDetail(userId, id);
    }

    /**
     * 订单列表
     */
    @GetMapping("/list")
    public List<OrderListVO> list(@RequestParam(value = "status", required = false) Integer status) {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        return orderService.list(userId, status);
    }
}
