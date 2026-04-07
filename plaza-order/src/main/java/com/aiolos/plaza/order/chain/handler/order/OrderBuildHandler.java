package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.OrderState;
import com.aiolos.plaza.enums.OrderType;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.model.po.Address;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.order.coreflow.inventory.model.InventoryProductSnapshot;
import com.aiolos.plaza.order.coreflow.product.ProductSnapshotReader;
import com.aiolos.plaza.order.domain.status.OrderStatusMetadataResolver;
import com.aiolos.plaza.orderno.provider.api.OrderNoApi;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class OrderBuildHandler implements ChainHandler<OrderCreateContext> {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private ParentOrderMapper parentOrderMapper;

    @Autowired
    private ProductSnapshotReader productSnapshotReader;

    @DubboReference
    private OrderNoApi orderNoApi;

    @Autowired
    private OrderStatusMetadataResolver orderStatusMetadataResolver;

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        String parentOrderSn = orderNoApi.nextParentOrderSn();
        context.setParentOrderSn(parentOrderSn);
        
        Address address = context.getAddress();
        var req = context.getReq();
        // 构单阶段统一通过商品快照读取组件取数，避免订单域直接依赖底层商品来源
        Map<Long, InventoryProductSnapshot> productSnapshotMap = productSnapshotReader.loadSnapshots(
                context.getCartItems().stream()
                        .map(CartItem::getProductId)
                        .distinct()
                        .collect(Collectors.toList())
        );

        for (Map.Entry<Long, List<CartItem>> entry : context.getShopCartMap().entrySet()) {
            Long shopId = entry.getKey();
            List<CartItem> shopItems = entry.getValue();

            String orderSn = orderNoApi.nextChildOrderSn();
            BigDecimal totalAmount = BigDecimal.ZERO;

            for (CartItem item : shopItems) {
                Long productId = item.getProductId();
                context.getAllCartIds().add(item.getId());

                InventoryProductSnapshot product = productSnapshotMap.get(productId);
                if (product == null || product.getStatus() == null || product.getStatus() != 1) {
                    ExceptionUtil.throwException(OrderExceptionEnum.PRODUCT_NOT_EXIST);
                }

                BigDecimal price = product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO;
                item.setPriceSnapshot(price);
                BigDecimal itemAmount = price.multiply(new BigDecimal(item.getQuantity()));
                totalAmount = totalAmount.add(itemAmount);
            }

            Order order = new Order();
            order.setOrderSn(orderSn);
            order.setParentOrderSn(parentOrderSn);
            order.setUserId(context.getUserId());
            order.setShopId(shopId);
            order.setOrderType(OrderType.NORMAL.getCode());
            order.setTotalAmount(totalAmount);
            order.setPayAmount(totalAmount);
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
            order.setCreateTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());
            order.setConfirmStatus(0);

            orderMapper.insert(order);
            context.getOrderIds().add(order.getId());

            for (CartItem cartItem : shopItems) {
                InventoryProductSnapshot product = productSnapshotMap.get(cartItem.getProductId());
                OrderItem orderItem = new OrderItem();
                orderItem.setOrderId(order.getId());
                orderItem.setOrderSn(order.getOrderSn());
                orderItem.setProductId(cartItem.getProductId());
                orderItem.setProductPic(product != null ? product.getProductImage() : cartItem.getProductImage());
                orderItem.setProductName(product != null ? product.getProductName() : cartItem.getProductName());
                orderItem.setProductPrice(cartItem.getPriceSnapshot());
                orderItem.setProductQuantity(cartItem.getQuantity());

                BigDecimal price = cartItem.getPriceSnapshot() != null ? cartItem.getPriceSnapshot() : BigDecimal.ZERO;
                orderItem.setRealAmount(price.multiply(new BigDecimal(cartItem.getQuantity())));

                orderItemMapper.insert(orderItem);
            }

            context.setParentTotalAmount(context.getParentTotalAmount().add(totalAmount));
        }

        ParentOrder parentOrder = new ParentOrder();
        parentOrder.setParentOrderSn(parentOrderSn);
        parentOrder.setUserId(context.getUserId());
        parentOrder.setTotalAmount(context.getParentTotalAmount());
        parentOrder.setPayAmount(context.getParentTotalAmount());
        orderStatusMetadataResolver.fill(parentOrder, OrderState.RESERVING.getCode());
        parentOrder.setPayType(req.getPayType());
        parentOrder.setOrderType(OrderType.NORMAL.getCode());
        parentOrder.setDeleteStatus(0);
        parentOrder.setCreateTime(LocalDateTime.now());
        parentOrder.setUpdateTime(LocalDateTime.now());
        parentOrderMapper.insert(parentOrder);
        
        chain.proceed(context);
    }

}
