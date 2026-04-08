package com.aiolos.plaza.order.domain.order.aggregate;

import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.OrderType;
import com.aiolos.plaza.model.po.Address;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.order.domain.order.status.OrderStatusMetadataResolver;
import com.aiolos.plaza.order.domain.stock.snapshot.InventoryProductSnapshot;
import com.aiolos.plaza.orderno.provider.api.OrderNoApi;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 订单聚合构建器
 * 负责把购物车提交数据组装成父单、子单与明细聚合
 */
@Component
public class OrderAggregateFactory {

    @DubboReference
    private OrderNoApi orderNoApi;

    private final OrderStatusMetadataResolver orderStatusMetadataResolver;

    public OrderAggregateFactory(OrderStatusMetadataResolver orderStatusMetadataResolver) {
        this.orderStatusMetadataResolver = orderStatusMetadataResolver;
    }

    public void build(OrderCreateContext context) {
        String parentOrderSn = orderNoApi.nextParentOrderSn();
        context.setParentOrderSn(parentOrderSn);

        Address address = context.getAddress();
        Map<Long, InventoryProductSnapshot> productSnapshotMap = context.getProductSnapshotMap();
        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<Long, List<CartItem>> entry : context.getShopCartMap().entrySet()) {
            Long shopId = entry.getKey();
            List<CartItem> shopItems = entry.getValue();

            String orderSn = orderNoApi.nextChildOrderSn();
            BigDecimal orderAmount = calculateOrderAmount(shopItems);
            Order order = buildOrder(context, address, parentOrderSn, orderSn, shopId, orderAmount, now);
            context.getPendingOrders().add(order);

            for (CartItem cartItem : shopItems) {
                context.getPendingOrderItems().add(buildOrderItem(orderSn, cartItem, productSnapshotMap));
            }

            context.setParentTotalAmount(context.getParentTotalAmount().add(orderAmount));
        }

        context.setPendingParentOrder(buildParentOrder(context, parentOrderSn, now));
    }

    private BigDecimal calculateOrderAmount(List<CartItem> shopItems) {
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CartItem item : shopItems) {
            BigDecimal price = item.getPriceSnapshot() != null ? item.getPriceSnapshot() : BigDecimal.ZERO;
            totalAmount = totalAmount.add(price.multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        return totalAmount;
    }

    private Order buildOrder(OrderCreateContext context,
                             Address address,
                             String parentOrderSn,
                             String orderSn,
                             Long shopId,
                             BigDecimal orderAmount,
                             LocalDateTime now) {
        var req = context.getReq();
        Order order = new Order();
        order.setOrderSn(orderSn);
        order.setParentOrderSn(parentOrderSn);
        order.setUserId(context.getUserId());
        order.setShopId(shopId);
        order.setOrderType(OrderType.NORMAL.getCode());
        order.setTotalAmount(orderAmount);
        order.setPayAmount(orderAmount);
        order.setFreightAmount(BigDecimal.ZERO);
        order.setPromotionAmount(BigDecimal.ZERO);
        order.setPayType(req.getPayType());
        // 普通单先落成“锁库存中”，由异步消息驱动库存预占，成功后再推进到待付款
        orderStatusMetadataResolver.fill(order, OrderState.RESERVING.getCode());
        order.setAddressId(req.getAddressId());
        order.setReceiverName(address.getName());
        order.setReceiverPhone(address.getTel());
        order.setReceiverProvince(address.getProvince());
        order.setReceiverCity(address.getCity());
        order.setReceiverRegion(address.getCounty());
        order.setReceiverDetailAddress(address.getAddressDetail());
        if (req.getShopNotes() != null && req.getShopNotes().containsKey(shopId)) {
            order.setNote(req.getShopNotes().get(shopId));
        }
        order.setDeleteStatus(0);
        order.setCreateTime(now);
        order.setUpdateTime(now);
        order.setConfirmStatus(0);
        return order;
    }

    private OrderItem buildOrderItem(String orderSn,
                                     CartItem cartItem,
                                     Map<Long, InventoryProductSnapshot> productSnapshotMap) {
        InventoryProductSnapshot product = productSnapshotMap.get(cartItem.getProductId());
        OrderItem orderItem = new OrderItem();
        orderItem.setOrderSn(orderSn);
        orderItem.setProductId(cartItem.getProductId());
        orderItem.setProductPic(product != null ? product.getProductImage() : cartItem.getProductImage());
        orderItem.setProductName(product != null ? product.getProductName() : cartItem.getProductName());
        orderItem.setProductPrice(cartItem.getPriceSnapshot());
        orderItem.setProductQuantity(cartItem.getQuantity());
        BigDecimal price = cartItem.getPriceSnapshot() != null ? cartItem.getPriceSnapshot() : BigDecimal.ZERO;
        orderItem.setRealAmount(price.multiply(BigDecimal.valueOf(cartItem.getQuantity())));
        return orderItem;
    }

    private ParentOrder buildParentOrder(OrderCreateContext context,
                                         String parentOrderSn,
                                         LocalDateTime now) {
        ParentOrder parentOrder = new ParentOrder();
        parentOrder.setParentOrderSn(parentOrderSn);
        parentOrder.setUserId(context.getUserId());
        parentOrder.setTotalAmount(context.getParentTotalAmount());
        parentOrder.setPayAmount(context.getParentTotalAmount());
        orderStatusMetadataResolver.fill(parentOrder, OrderState.RESERVING.getCode());
        parentOrder.setPayType(context.getReq().getPayType());
        parentOrder.setOrderType(OrderType.NORMAL.getCode());
        parentOrder.setDeleteStatus(0);
        parentOrder.setCreateTime(now);
        parentOrder.setUpdateTime(now);
        return parentOrder;
    }
}
