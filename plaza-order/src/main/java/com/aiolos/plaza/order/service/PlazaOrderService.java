package com.aiolos.plaza.order.service;

import com.aiolos.plaza.order.model.bo.OrderSubmitReq;
import com.aiolos.plaza.order.model.vo.OrderListVO;
import java.util.List;

public interface PlazaOrderService {

    /**
     * 提交订单
     * @param userId 用户ID
     * @param req 提交请求
     * @return 支付单号 (ParentOrderSn)
     */
    String submit(Long userId, OrderSubmitReq req);

    /**
     * 获取支付单详情
     */
    OrderListVO getPayInfo(Long userId, String paySn);

    /**
     * 查询订单详情
     * @param userId 用户ID
     * @param orderId 订单ID
     * @return 订单详情
     */
    OrderListVO getDetail(Long userId, Long orderId);

    /**
     * 查询订单列表
     * @param userId 用户ID
     * @param status 订单状态（可选）
     * @return 订单列表
     */
    List<OrderListVO> list(Long userId, Integer status);

    /**
     * 处理超时未支付订单（批量）
     */
    void cancelTimeoutOrders();

    /**
     * 取消单个订单（MQ触发）
     * @param orderId 订单ID
     */
    void cancelOrder(Long orderId);
    /**
     * 支付
     *
     * @param userId  用户ID
     * @param orderSn 订单号
     * @param payType 支付方式
     * @return 支付表单HTML
     */
    String pay(Long userId, String orderSn, Integer payType, boolean isMobile);

    /**
     * 支付回调处理
     *
     * @param params 支付宝回调参数
     * @return success/fail
     */
    String payNotify(java.util.Map<String, String> params);
}
