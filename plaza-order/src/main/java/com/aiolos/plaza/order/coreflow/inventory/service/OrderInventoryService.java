package com.aiolos.plaza.order.coreflow.inventory.service;

import com.aiolos.plaza.order.coreflow.inventory.model.InventoryReserveItem;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderInventoryService {
    String reserve(String orderSn, Long userId, List<InventoryReserveItem> items, LocalDateTime expireTime);

    void confirm(String reservationNo);

    void release(String reservationNo);

    void rollbackConfirmed(String reservationNo);

    void extendExpireTime(String reservationNo, LocalDateTime newExpireTime);

    void expireReservations(int batchSize);
}
