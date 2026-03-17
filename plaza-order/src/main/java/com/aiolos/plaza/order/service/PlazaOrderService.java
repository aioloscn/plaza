package com.aiolos.plaza.order.service;

import com.aiolos.plaza.order.dto.OrderSubmitReq;
import com.aiolos.plaza.order.vo.OrderListVO;
import java.util.List;

public interface PlazaOrderService {

    /**
     * 提交订单
     * @param userId 用户ID
     * @param req 提交请求
     * @return 订单ID
     */
    Long submit(Long userId, OrderSubmitReq req);

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
}
