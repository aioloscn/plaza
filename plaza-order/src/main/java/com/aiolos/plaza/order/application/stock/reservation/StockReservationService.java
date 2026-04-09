package com.aiolos.plaza.order.application.stock.reservation;

import com.aiolos.plaza.enums.StockScope;
import com.aiolos.plaza.order.domain.stock.reservation.InventoryReserveItem;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存预占应用服务
 * 负责预占、确认、释放、回滚与过期处理的统一入口
 */
public interface StockReservationService {

    /**
     * 冻结库存并创建预占记录
     */
    String reserve(String orderSn, Long userId, StockScope stockScope, Long activityId, List<InventoryReserveItem> items, LocalDateTime expireTime);

    /**
     * 支付成功后确认预占
     */
    void confirm(String reservationNo);

    /**
     * 关闭订单时释放冻结库存
     */
    void release(String reservationNo);

    /**
     * 已确认库存在退款成功后回补
     */
    void rollbackConfirmed(String reservationNo);

    /**
     * 延长预占过期时间
     */
    void extendExpireTime(String reservationNo, LocalDateTime newExpireTime);

    /**
     * 批量处理已过期预占
     */
    void expireReservations(int batchSize);
}
