package com.aiolos.plaza.order.domain.order.snapshot;

import com.aiolos.plaza.enums.ProductBizType;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.model.po.Product;
import com.aiolos.plaza.order.domain.stock.snapshot.InventoryProductSnapshot;
import com.aiolos.plaza.product.model.dto.ProductOrderSkuSnapshotDTO;
import com.aiolos.plaza.product.service.facade.ProductSnapshotFacade;
import com.aiolos.plaza.service.ProductService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 订单确认单/下单链路统一商品快照加载器
 * 外卖场景兼容旧 product 表，电商场景统一走 plaza-product Facade
 */
@Component
public class OrderProductSnapshotLoader {

    private final ProductService productService;
    private final ProductSnapshotFacade productSnapshotFacade;

    public OrderProductSnapshotLoader(ProductService productService,
                                      ProductSnapshotFacade productSnapshotFacade) {
        this.productService = productService;
        this.productSnapshotFacade = productSnapshotFacade;
    }

    public Map<String, InventoryProductSnapshot> loadSnapshots(List<CartItem> cartItems) {
        if (cartItems == null || cartItems.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<ProductBizType, List<Long>> skuIdsByBizType = new LinkedHashMap<>();
        for (CartItem cartItem : cartItems) {
            if (cartItem == null || cartItem.getSkuId() == null) {
                continue;
            }
            skuIdsByBizType.computeIfAbsent(resolveBizType(cartItem.getBizType()), key -> new ArrayList<>())
                    .add(cartItem.getSkuId());
        }
        if (skuIdsByBizType.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, InventoryProductSnapshot> result = new LinkedHashMap<>();
        for (Map.Entry<ProductBizType, List<Long>> entry : skuIdsByBizType.entrySet()) {
            List<Long> skuIds = distinctIds(entry.getValue());
            if (skuIds.isEmpty()) {
                continue;
            }
            if (ProductBizType.ECOMMERCE.equals(entry.getKey())) {
                appendEcommerceSnapshots(result, skuIds);
                continue;
            }
            appendLocalRetailSnapshots(result, skuIds);
        }
        return result;
    }

    public static String buildSnapshotKey(Integer bizType, Long skuId) {
        ProductBizType resolvedBizType = ProductBizType.fromCode(bizType);
        Integer bizTypeCode = resolvedBizType == null ? ProductBizType.LOCAL_RETAIL.getCode() : resolvedBizType.getCode();
        return bizTypeCode + ":" + skuId;
    }

    private void appendLocalRetailSnapshots(Map<String, InventoryProductSnapshot> result, List<Long> skuIds) {
        List<Product> products = productService.listByIds(skuIds);
        if (products == null || products.isEmpty()) {
            return;
        }
        for (Product product : products) {
            if (product == null || product.getId() == null) {
                continue;
            }
            InventoryProductSnapshot snapshot = new InventoryProductSnapshot();
            snapshot.setSkuId(product.getId());
            snapshot.setBizType(ProductBizType.LOCAL_RETAIL.getCode());
            snapshot.setShopId(product.getShopId());
            snapshot.setProductName(product.getName());
            snapshot.setProductImage(product.getImageUrl());
            snapshot.setStatus(product.getStatus());
            snapshot.setStock(product.getStock());
            snapshot.setPrice(product.getPrice());
            result.put(buildSnapshotKey(ProductBizType.LOCAL_RETAIL.getCode(), product.getId()), snapshot);
        }
    }

    private void appendEcommerceSnapshots(Map<String, InventoryProductSnapshot> result, List<Long> skuIds) {
        Map<Long, ProductOrderSkuSnapshotDTO> snapshotMap =
                productSnapshotFacade.batchGetOrderSkuSnapshots(skuIds, ProductBizType.ECOMMERCE);
        if (snapshotMap == null || snapshotMap.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, ProductOrderSkuSnapshotDTO> entry : snapshotMap.entrySet()) {
            ProductOrderSkuSnapshotDTO orderSkuSnapshot = entry.getValue();
            if (orderSkuSnapshot == null || orderSkuSnapshot.getSkuId() == null) {
                continue;
            }
            InventoryProductSnapshot snapshot = new InventoryProductSnapshot();
            snapshot.setSkuId(orderSkuSnapshot.getSkuId());
            snapshot.setBizType(resolveBizType(orderSkuSnapshot.getBizType()).getCode());
            snapshot.setShopId(orderSkuSnapshot.getShopId());
            snapshot.setProductName(orderSkuSnapshot.getSkuName());
            snapshot.setProductImage(orderSkuSnapshot.getImageUrl());
            snapshot.setStatus(orderSkuSnapshot.getStatus());
            snapshot.setStock(orderSkuSnapshot.getAvailableStock());
            snapshot.setPrice(orderSkuSnapshot.getSalePrice());
            result.put(buildSnapshotKey(ProductBizType.ECOMMERCE.getCode(), orderSkuSnapshot.getSkuId()), snapshot);
        }
    }

    private List<Long> distinctIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> result = ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new ArrayList<>(result);
    }

    private ProductBizType resolveBizType(Integer bizTypeCode) {
        ProductBizType bizType = ProductBizType.fromCode(bizTypeCode);
        return bizType == null ? ProductBizType.LOCAL_RETAIL : bizType;
    }
}
