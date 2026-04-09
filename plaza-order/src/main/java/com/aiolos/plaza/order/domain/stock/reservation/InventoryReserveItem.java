package com.aiolos.plaza.order.domain.stock.reservation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReserveItem {
    private Long productId;
    private Long activityId;
    private Integer quantity;
}
