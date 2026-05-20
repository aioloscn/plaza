package com.aiolos.plaza.order.domain.stock.reservation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReserveItem {
    /**
     * 普通单和电商统一使用真实 skuId；秒杀场景当前仍复用旧商品主键体系
     */
    private Long skuId;

    private Long activityId;

    private Integer quantity;
}
