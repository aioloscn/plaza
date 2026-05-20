package com.aiolos.plaza.order.domain.order.snapshot;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.aiolos.plaza.order.domain.stock.snapshot.InventoryProductSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 下单商品快照校验器
 * 负责统一拉取商品快照并回填购物车提交快照
 */
@Component
public class OrderSnapshotValidator {

    private final OrderProductSnapshotLoader orderProductSnapshotLoader;

    public OrderSnapshotValidator(OrderProductSnapshotLoader orderProductSnapshotLoader) {
        this.orderProductSnapshotLoader = orderProductSnapshotLoader;
    }

    public void validateAndAttach(OrderCreateContext context) {
        Map<String, InventoryProductSnapshot> productSnapshotMap =
                orderProductSnapshotLoader.loadSnapshots(context.getCartItems());
        context.setProductSnapshotMap(productSnapshotMap);

        for (List<CartItem> shopItems : context.getShopCartMap().values()) {
            for (CartItem item : shopItems) {
                context.getAllCartIds().add(item.getId());
                InventoryProductSnapshot product = productSnapshotMap.get(
                        OrderProductSnapshotLoader.buildSnapshotKey(item.getBizType(), item.getSkuId())
                );
                if (product == null || product.getStatus() == null || product.getStatus() != 1) {
                    ExceptionUtil.throwException(OrderExceptionEnum.PRODUCT_NOT_EXIST);
                }
                BigDecimal price = product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO;
                item.setPriceSnapshot(price);
            }
        }
    }
}
