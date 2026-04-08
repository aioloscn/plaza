package com.aiolos.plaza.order.application.order;

import com.aiolos.plaza.order.model.bo.OrderSubmitReq;
import com.aiolos.plaza.order.model.vo.OrderConfirmVO;
import com.aiolos.plaza.order.model.vo.OrderListVO;
import java.util.List;

public interface OrderService {

    OrderConfirmVO confirm(Long userId, OrderSubmitReq req);

    /**
     * 提交订单
     * @param userId 用户ID
     * @param req 提交请求
     * @return 支付单号（ParentOrderSn）
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
     * @param status 订单状态（可空）
     * @return 订单列表
     */
    List<OrderListVO> list(Long userId, Integer status);

    /**
     * 批量处理超时未支付订单
     */
    void cancelTimeoutOrders();

    /**
     * 父订单状态对账：
     * 按批次扫描父订单并基于子订单聚合规则做状态自愈
     * @param batchSize 扫描批次大小，`<= 0` 时使用默认值
     */
    void reconcileParentOrderStatus(int batchSize);

    /**
     * 取消单个订单（MQ 触发）
     * @param orderId 订单ID
     */
    void cancelOrder(Long orderId);
}
