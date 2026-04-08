package com.aiolos.plaza.order.domain.stock.release;

import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OrderStockReleaseContext {

    private Order order;
    private List<OrderItem> orderItems = new ArrayList<>();
    private List<OrderItem> redisIncrementedItems = new ArrayList<>();
    private boolean seckillBoughtUserRemoved;
}
