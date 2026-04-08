package com.aiolos.plaza.order.chain.context;

import com.aiolos.plaza.model.po.Address;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.domain.stock.snapshot.InventoryProductSnapshot;
import com.aiolos.plaza.order.model.bo.OrderSubmitReq;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.aiolos.plaza.model.po.MqLocalMessage;

@Data
@EqualsAndHashCode(callSuper = true)
public class OrderCreateContext extends TradeContext {
    private OrderSubmitReq req;
    private Address address;
    private List<CartItem> cartItems = new ArrayList<>();
    private Map<Long, List<CartItem>> shopCartMap;
    private String parentOrderSn;
    private BigDecimal parentTotalAmount = BigDecimal.ZERO;
    private List<Long> allCartIds = new ArrayList<>();
    private List<Long> orderIds = new ArrayList<>();
    private List<MqLocalMessage> localMessages = new ArrayList<>();
    private Map<Long, InventoryProductSnapshot> productSnapshotMap;
    private List<Order> pendingOrders = new ArrayList<>();
    private List<OrderItem> pendingOrderItems = new ArrayList<>();
    private ParentOrder pendingParentOrder;
}
