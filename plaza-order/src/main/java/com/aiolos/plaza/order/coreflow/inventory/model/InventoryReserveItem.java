package com.aiolos.plaza.order.coreflow.inventory.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryReserveItem {
    private Long productId;
    private Integer quantity;
}
