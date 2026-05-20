package com.aiolos.plaza.cart.service.impl;

import com.aiolos.common.enums.error.ErrorEnum;
import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.cart.model.bo.CartAddReq;
import com.aiolos.plaza.cart.model.bo.CartCheckReq;
import com.aiolos.plaza.cart.model.bo.CartUpdateReq;
import com.aiolos.plaza.cart.service.PlazaCartService;
import com.aiolos.plaza.cart.model.vo.CartItemVO;
import com.aiolos.plaza.cart.mq.producer.CartSaveProducer;
import com.aiolos.plaza.cart.model.vo.CartListVO;
import com.aiolos.plaza.enums.ProductBizType;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.model.po.Shop;
import com.aiolos.plaza.product.model.dto.ProductCartSkuSnapshotDTO;
import com.aiolos.plaza.product.service.facade.ProductSnapshotFacade;
import com.aiolos.plaza.service.CartItemService;
import com.aiolos.plaza.service.ShopService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.aiolos.plaza.mq.message.CartAsyncSaveMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlazaCartServiceImpl implements PlazaCartService {

    @Resource
    private ShopService shopService;

    @Resource
    private CartItemService cartItemService;

    @Resource
    private ProductSnapshotFacade productSnapshotFacade;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CartSaveProducer cartChangeProducer;

    @Autowired
    private ObjectMapper objectMapper;

    private static class CartProductSnapshot {
        private Long skuId;
        private Integer bizType;
        private Long shopId;
        private String productName;
        private String productImage;
        private BigDecimal price;
        private Integer stock;
        private Integer status;
    }

    private String getCartKey(Long userId, String deviceId) {
        if (userId != null && userId > 0) {
            return RedisKeyEnum.CART_USER.getKey(userId);
        } else if (deviceId != null && !deviceId.isEmpty()) {
            return RedisKeyEnum.CART_TEMP.getKey(deviceId);
        }
        ExceptionUtil.throwException(ErrorEnum.NULL_POINT_ERROR.setErrMsg("用户未登录且无设备ID"));
        return null;
    }

    private String getCartEmptyMarkKey(Long userId, String deviceId) {
        if (userId != null && userId > 0) {
            return RedisKeyEnum.CART_EMPTY_MARK_USER.getKey(userId);
        } else if (deviceId != null && !deviceId.isEmpty()) {
            return RedisKeyEnum.CART_EMPTY_MARK_TEMP.getKey(deviceId);
        }
        return null;
    }

    private BoundHashOperations<String, String, String> getCartOps(String cartKey) {
        return stringRedisTemplate.boundHashOps(cartKey);
    }

    private void setCartExpire(String cartKey, Long userId) {
        if (userId == null || userId <= 0) {
            // 游客购物车7天过期
            stringRedisTemplate.expire(cartKey, 7, TimeUnit.DAYS);
        }
    }

    private boolean isLoggedIn(Long userId) {
        return userId != null && userId > 0;
    }

    private Long nextCartItemId() {
        return IdWorker.getId();
    }

    private String getCartItemField(Long cartItemId) {
        return String.valueOf(cartItemId);
    }

    private CartItemVO readCartItem(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, CartItemVO.class);
    }

    private void writeCartItem(BoundHashOperations<String, String, String> hashOps, CartItemVO itemVO) throws JsonProcessingException {
        hashOps.put(getCartItemField(itemVO.getId()), objectMapper.writeValueAsString(itemVO));
    }

    private CartAsyncSaveMessage buildUpsertMessage(Long userId, CartItemVO itemVO) {
        return new CartAsyncSaveMessage(
                userId,
                itemVO.getShopId(),
                itemVO.getSkuId(),
                itemVO.getBizType(),
                itemVO.getId(),
                null,
                itemVO.getQuantity(),
                Boolean.TRUE.equals(itemVO.getChecked()) ? 1 : 0,
                itemVO.getPrice(),
                itemVO.getProductName(),
                itemVO.getProductImage(),
                1,
                1
        );
    }

    private CartAsyncSaveMessage buildDeleteMessage(Long userId, CartItemVO itemVO) {
        return new CartAsyncSaveMessage(
                userId,
                itemVO == null ? null : itemVO.getShopId(),
                itemVO == null ? null : itemVO.getSkuId(),
                itemVO == null ? null : itemVO.getBizType(),
                itemVO == null ? null : itemVO.getId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                2
        );
    }

    private ProductBizType resolveBizType(Integer bizTypeCode) {
        ProductBizType bizType = ProductBizType.fromCode(bizTypeCode);
        return bizType == null ? ProductBizType.LOCAL_RETAIL : bizType;
    }

    private String buildSnapshotKey(Integer bizTypeCode, Long skuId) {
        return resolveBizType(bizTypeCode).getCode() + ":" + skuId;
    }

    private CartProductSnapshot buildSnapshot(ProductCartSkuSnapshotDTO skuSnapshot) {
        if (skuSnapshot == null) {
            return null;
        }
        CartProductSnapshot snapshot = new CartProductSnapshot();
        snapshot.skuId = skuSnapshot.getSkuId();
        snapshot.bizType = resolveBizType(skuSnapshot.getBizType()).getCode();
        snapshot.shopId = skuSnapshot.getShopId();
        snapshot.productName = skuSnapshot.getSkuName();
        snapshot.productImage = skuSnapshot.getImageUrl();
        snapshot.price = skuSnapshot.getSalePrice();
        snapshot.stock = skuSnapshot.getAvailableStock();
        snapshot.status = skuSnapshot.getStatus();
        return snapshot;
    }

    private CartProductSnapshot getProductSnapshot(Long skuId, Integer bizTypeCode) {
        ProductBizType bizType = resolveBizType(bizTypeCode);
        if (skuId == null) {
            return null;
        }
        return buildSnapshot(productSnapshotFacade.getCartSkuSnapshot(skuId, bizType));
    }

    private void appendSnapshotGroup(Map<String, CartProductSnapshot> snapshotMap,
                                     List<Long> skuIds,
                                     ProductBizType bizType) {
        if (skuIds == null || skuIds.isEmpty()) {
            return;
        }
        Map<Long, ProductCartSkuSnapshotDTO> skuSnapshotMap = productSnapshotFacade.batchGetCartSkuSnapshots(skuIds, bizType);
        for (Map.Entry<Long, ProductCartSkuSnapshotDTO> entry : skuSnapshotMap.entrySet()) {
            CartProductSnapshot snapshot = buildSnapshot(entry.getValue());
            if (snapshot != null) {
                snapshotMap.put(buildSnapshotKey(bizType.getCode(), entry.getKey()), snapshot);
            }
        }
    }

    private Map<String, CartProductSnapshot> loadProductSnapshotMap(List<CartItemVO> cartItems) {
        if (cartItems == null || cartItems.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<ProductBizType, List<Long>> skuIdsByBizType = new HashMap<>();
        for (CartItemVO cartItem : cartItems) {
            if (cartItem == null || cartItem.getSkuId() == null) {
                continue;
            }
            skuIdsByBizType.computeIfAbsent(resolveBizType(cartItem.getBizType()), key -> new ArrayList<>())
                    .add(cartItem.getSkuId());
        }
        return loadProductSnapshotMap(skuIdsByBizType);
    }

    private Map<String, CartProductSnapshot> loadProductSnapshotMapFromDb(List<CartItem> cartItems) {
        if (cartItems == null || cartItems.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<ProductBizType, List<Long>> skuIdsByBizType = new HashMap<>();
        for (CartItem cartItem : cartItems) {
            if (cartItem == null || cartItem.getSkuId() == null) {
                continue;
            }
            skuIdsByBizType.computeIfAbsent(resolveBizType(cartItem.getBizType()), key -> new ArrayList<>())
                    .add(cartItem.getSkuId());
        }
        return loadProductSnapshotMap(skuIdsByBizType);
    }

    private Map<String, CartProductSnapshot> loadProductSnapshotMap(Map<ProductBizType, List<Long>> skuIdsByBizType) {
        if (skuIdsByBizType == null || skuIdsByBizType.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, CartProductSnapshot> snapshotMap = new HashMap<>();
        for (Map.Entry<ProductBizType, List<Long>> entry : skuIdsByBizType.entrySet()) {
            List<Long> skuIds = entry.getValue().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            appendSnapshotGroup(snapshotMap, skuIds, entry.getKey());
        }
        return snapshotMap;
    }

    private Map<Long, String> loadShopNameMap(List<Long> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return shopService.listByIds(shopIds).stream()
                .collect(Collectors.toMap(Shop::getId, Shop::getName));
    }

    private CartItemVO createCartItemVO(CartProductSnapshot productSnapshot, Long shopId, String shopName, Integer count) {
        CartItemVO itemVO = new CartItemVO();
        itemVO.setId(nextCartItemId());
        itemVO.setSkuId(productSnapshot.skuId);
        itemVO.setBizType(productSnapshot.bizType);
        itemVO.setShopId(shopId);
        itemVO.setShopName(shopName);
        itemVO.setProductName(productSnapshot.productName);
        itemVO.setProductImage(productSnapshot.productImage);
        itemVO.setPrice(productSnapshot.price);
        itemVO.setQuantity(count);
        itemVO.setChecked(true);
        itemVO.setStock(productSnapshot.stock);
        itemVO.setStatus("VALID");
        return itemVO;
    }

    private int normalizeCount(Integer count) {
        return count == null || count <= 0 ? 1 : count;
    }

    private int safeQuantity(Integer quantity) {
        return quantity == null || quantity <= 0 ? 0 : quantity;
    }

    private boolean sameCartItem(CartItemVO itemVO, Long skuId, Long shopId, Integer bizTypeCode) {
        return itemVO != null
                && Objects.equals(itemVO.getSkuId(), skuId)
                && Objects.equals(resolveBizType(itemVO.getBizType()).getCode(), resolveBizType(bizTypeCode).getCode())
                && Objects.equals(itemVO.getShopId(), shopId);
    }

    /**
     * 登录态下 Redis 冷启动时，先把 DB 中已有购物车同步到 Redis，避免重复加购生成新行
     */
    private void hydrateCartFromDbIfNeeded(Long userId, String deviceId,
                                           BoundHashOperations<String, String, String> hashOps) throws JsonProcessingException {
        if (!isLoggedIn(userId)) {
            return;
        }
        Long size = hashOps.size();
        if (size != null && size > 0) {
            return;
        }

        String emptyMarkKey = getCartEmptyMarkKey(userId, deviceId);
        if (StringUtils.isNotBlank(emptyMarkKey) && Boolean.TRUE.equals(stringRedisTemplate.hasKey(emptyMarkKey))) {
            return;
        }

        List<CartItem> dbItems = cartItemService.lambdaQuery()
                .eq(CartItem::getUserId, userId)
                .list();
        if (dbItems == null || dbItems.isEmpty()) {
            return;
        }

        Map<String, CartProductSnapshot> productMap = loadProductSnapshotMapFromDb(dbItems);
        Map<Long, String> shopNameMap = loadShopNameMap(dbItems.stream()
                .map(CartItem::getShopId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList()));

        for (CartItem dbItem : dbItems) {
            CartProductSnapshot product = productMap.get(buildSnapshotKey(dbItem.getBizType(), dbItem.getSkuId()));
            CartItemVO itemVO = buildVOFromDb(dbItem, product, shopNameMap);
            writeCartItem(hashOps, itemVO);
        }
    }

    private List<CartItemVO> findCartItems(BoundHashOperations<String, String, String> hashOps,
                                           Long skuId, Long shopId, Integer bizTypeCode) throws JsonProcessingException {
        Map<String, String> entries = hashOps.entries();
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }

        List<CartItemVO> matchedItems = new ArrayList<>();
        for (String json : entries.values()) {
            CartItemVO itemVO = readCartItem(json);
            if (sameCartItem(itemVO, skuId, shopId, bizTypeCode)) {
                matchedItems.add(itemVO);
            }
        }
        return matchedItems;
    }

    private CartItemVO buildVOFromDb(CartItem dbItem, CartProductSnapshot product, Map<Long, String> shopNameMap) {
        CartItemVO itemVO = new CartItemVO();
        itemVO.setId(dbItem.getId());
        itemVO.setSkuId(dbItem.getSkuId());
        itemVO.setBizType(resolveBizType(dbItem.getBizType()).getCode());
        itemVO.setShopId(dbItem.getShopId());
        itemVO.setShopName(shopNameMap.getOrDefault(dbItem.getShopId(), "未知店铺"));
        itemVO.setQuantity(dbItem.getQuantity());
        itemVO.setChecked(dbItem.getChecked() != null && dbItem.getChecked() == 1);
        if (product == null) {
            itemVO.setProductName(dbItem.getProductName());
            itemVO.setProductImage(dbItem.getProductImage());
            itemVO.setPrice(dbItem.getPriceSnapshot());
            itemVO.setStock(0);
            itemVO.setStatus("INVALID");
            return itemVO;
        }
        itemVO.setProductName(product.productName);
        itemVO.setProductImage(product.productImage);
        itemVO.setPrice(product.price);
        itemVO.setStock(product.stock);
        itemVO.setStatus(resolveStatus(product, dbItem.getQuantity()));
        return itemVO;
    }

    private String resolveStatus(CartProductSnapshot product, Integer quantity) {
        if (product == null) {
            return "INVALID";
        }
        if (product.status == null || product.status == 0) {
            return "OFF_SHELF";
        }
        if (product.stock == null || quantity == null || product.stock < quantity) {
            return "NO_STOCK";
        }
        return "VALID";
    }

    /**
     * 标记购物车已被清空，阻止短期内的 MySQL 回源
     */
    private void markCartAsEmpty(Long userId, String deviceId) {
        String emptyMarkKey = getCartEmptyMarkKey(userId, deviceId);
        if (emptyMarkKey != null) {
            // 设置 60 秒有效期，足以覆盖 MQ 异步删除的延迟
            stringRedisTemplate.opsForValue().set(emptyMarkKey, "1", 60, TimeUnit.SECONDS);
        }
    }

    @Override
    public void addCart(Long userId, String deviceId, CartAddReq req) {
        String cartKey = getCartKey(userId, deviceId);
        BoundHashOperations<String, String, String> hashOps = getCartOps(cartKey);

        try {
            ProductBizType bizType = resolveBizType(req.getBizType());
            CartProductSnapshot product = getProductSnapshot(req.getSkuId(), bizType.getCode());
            if (product == null || product.status == null || product.status == 0) {
                throw new RuntimeException("商品不存在或已下架");
            }
            Long shopId = req.getShopId() != null ? req.getShopId() : product.shopId;
            Shop shop = shopService.getById(shopId);
            String shopName = shop != null ? shop.getName() : "未知店铺";
            int addCount = normalizeCount(req.getCount());

            hydrateCartFromDbIfNeeded(userId, deviceId, hashOps);
            List<CartItemVO> matchedItems = findCartItems(hashOps, product.skuId, shopId, bizType.getCode());

            CartItemVO itemVO;
            if (matchedItems.isEmpty()) {
                itemVO = createCartItemVO(product, shopId, shopName, addCount);
            } else {
                // 同店铺同商品始终复用同一个购物车项，只做数量累加
                itemVO = matchedItems.get(0);
                int mergedQuantity = safeQuantity(itemVO.getQuantity()) + addCount;
                itemVO.setBizType(bizType.getCode());
                itemVO.setShopName(shopName);
                itemVO.setProductName(product.productName);
                itemVO.setProductImage(product.productImage);
                itemVO.setPrice(product.price);
                itemVO.setQuantity(mergedQuantity);
                itemVO.setChecked(true);
                itemVO.setStock(product.stock);
                itemVO.setStatus(resolveStatus(product, mergedQuantity));
            }

            writeCartItem(hashOps, itemVO);
            setCartExpire(cartKey, userId);

            if (isLoggedIn(userId)) {
                cartChangeProducer.sendCartSaveMessage(buildUpsertMessage(userId, itemVO));
            }
        } catch (JsonProcessingException e) {
            log.error("添加购物车失败", e);
            throw new RuntimeException("添加购物车失败");
        }
    }

    @Override
    public void updateQuantity(Long userId, String deviceId, CartUpdateReq req) {
        String cartKey = getCartKey(userId, deviceId);
        BoundHashOperations<String, String, String> hashOps = getCartOps(cartKey);
        String cartItemField = getCartItemField(req.getCartItemId());

        try {
            if (Boolean.TRUE.equals(hashOps.hasKey(cartItemField))) {
                String json = hashOps.get(cartItemField);
                CartItemVO itemVO = readCartItem(json);
                itemVO.setQuantity(req.getCount());
                writeCartItem(hashOps, itemVO);
                setCartExpire(cartKey, userId);

                if (isLoggedIn(userId)) {
                    cartChangeProducer.sendCartSaveMessage(buildUpsertMessage(userId, itemVO));
                }
            }
        } catch (JsonProcessingException e) {
            log.error("更新购物车数量失败", e);
            throw new RuntimeException("更新购物车数量失败");
        }
    }

    @Override
    public void deleteCartItem(Long userId, String deviceId, Long cartItemId) {
        String cartKey = getCartKey(userId, deviceId);
        BoundHashOperations<String, String, String> hashOps = getCartOps(cartKey);
        String cartItemField = getCartItemField(cartItemId);
        CartItemVO removedItem = null;
        try {
            String json = hashOps.get(cartItemField);
            if (json != null) {
                removedItem = readCartItem(json);
            }
        } catch (JsonProcessingException e) {
            log.warn("解析待删除购物车项失败, cartItemId={}", cartItemId, e);
        }
        hashOps.delete(cartItemField);

        // 如果删除后购物车为空，设置短期标记阻止回源
        Long size = hashOps.size();
        if (size == null || size == 0) {
            markCartAsEmpty(userId, deviceId);
        }

        // 异步删除 MySQL 中的记录
        if (isLoggedIn(userId)) {
            cartChangeProducer.sendCartSaveMessage(buildDeleteMessage(userId, removedItem == null ? new CartItemVO() : removedItem));
        }
    }

    @Override
    public void checkCartItem(Long userId, String deviceId, CartCheckReq req) {
        String cartKey = getCartKey(userId, deviceId);
        BoundHashOperations<String, String, String> hashOps = getCartOps(cartKey);

        try {
            if (req.getCartItemIds() != null && !req.getCartItemIds().isEmpty()) {
                for (Long cartItemId : req.getCartItemIds()) {
                    String cartItemField = getCartItemField(cartItemId);
                    if (Boolean.TRUE.equals(hashOps.hasKey(cartItemField))) {
                        String json = hashOps.get(cartItemField);
                        CartItemVO itemVO = readCartItem(json);
                        itemVO.setChecked(req.getChecked() == 1);
                        writeCartItem(hashOps, itemVO);
                    }
                }
            } else {
                // 全选/全不选
                Map<String, String> entries = hashOps.entries();
                if (entries != null) {
                    for (Map.Entry<String, String> entry : entries.entrySet()) {
                        CartItemVO itemVO = readCartItem(entry.getValue());
                        itemVO.setChecked(req.getChecked() == 1);
                        writeCartItem(hashOps, itemVO);
                    }
                }
            }
            setCartExpire(cartKey, userId);
        } catch (JsonProcessingException e) {
            log.error("更新选中状态失败", e);
            throw new RuntimeException("更新选中状态失败");
        }
    }

    @Override
    public CartListVO getCartList(Long userId, String deviceId) {
        String cartKey = getCartKey(userId, deviceId);
        BoundHashOperations<String, String, String> hashOps = getCartOps(cartKey);

        List<CartItemVO> items = new ArrayList<>();
        BigDecimal totalPrice = BigDecimal.ZERO;
        List<String> invalidCartItemIds = new ArrayList<>();

        try {
            Map<String, String> entries = hashOps.entries();
            // 如果 Redis 为空且用户已登录，尝试从 MySQL 加载数据
            if ((entries == null || entries.isEmpty()) && isLoggedIn(userId)) {
                // 防幽灵商品：检查是否有近期清空标记
                String emptyMarkKey = getCartEmptyMarkKey(userId, deviceId);
                if (StringUtils.isNotBlank(emptyMarkKey) && Boolean.TRUE.equals(stringRedisTemplate.hasKey(emptyMarkKey))) {
                    // 刚刚清空过，直接返回空，防止 MySQL 脏数据回源
                    CartListVO listVO = new CartListVO();
                    listVO.setItems(items);
                    listVO.setTotalPrice(totalPrice);
                    return listVO;
                }

                List<CartItem> dbItems = cartItemService.lambdaQuery().eq(CartItem::getUserId, userId).list();

                if (dbItems != null && !dbItems.isEmpty()) {
                    Map<String, CartProductSnapshot> productMap = loadProductSnapshotMapFromDb(dbItems);
                    Map<Long, String> shopNameMap = loadShopNameMap(dbItems.stream()
                            .map(CartItem::getShopId)
                            .filter(Objects::nonNull)
                            .distinct()
                            .collect(Collectors.toList()));

                    for (CartItem dbItem : dbItems) {
                        CartProductSnapshot product = productMap.get(buildSnapshotKey(dbItem.getBizType(), dbItem.getSkuId()));
                        CartItemVO itemVO = buildVOFromDb(dbItem, product, shopNameMap);
                        writeCartItem(hashOps, itemVO);
                        items.add(itemVO);
                        if (Boolean.TRUE.equals(itemVO.getChecked()) && "VALID".equals(itemVO.getStatus())) {
                            BigDecimal itemTotal = itemVO.getPrice().multiply(new BigDecimal(itemVO.getQuantity()));
                            totalPrice = totalPrice.add(itemTotal);
                        }
                    }
                    CartListVO listVO = new CartListVO();
                    listVO.setItems(items);
                    listVO.setTotalPrice(totalPrice);
                    return listVO;
                }
            }

            if (entries != null && !entries.isEmpty()) {
                List<CartItemVO> redisItems = new ArrayList<>();

                // 1. 从 Redis 获取购物车数据
                for (String json : entries.values()) {
                    CartItemVO itemVO = readCartItem(json);
                    redisItems.add(itemVO);
                }

                // 2. 批量查询数据库获取最新价格、库存和状态
                Map<String, CartProductSnapshot> productMap = loadProductSnapshotMap(redisItems);

                for (CartItemVO item : redisItems) {
                    item.setBizType(resolveBizType(item.getBizType()).getCode());
                    CartProductSnapshot product = productMap.get(buildSnapshotKey(item.getBizType(), item.getSkuId()));
                    if (product == null) {
                        item.setStatus("INVALID");
                        item.setStock(0);
                        invalidCartItemIds.add(getCartItemField(item.getId()));
                    } else {
                        item.setProductName(product.productName);
                        item.setProductImage(product.productImage);
                        item.setPrice(product.price);
                        item.setStock(product.stock);
                        item.setStatus(resolveStatus(product, item.getQuantity()));
                    }
                    items.add(item);

                    if (Boolean.TRUE.equals(item.getChecked()) && "VALID".equals(item.getStatus())) {
                        BigDecimal itemTotal = item.getPrice().multiply(new BigDecimal(item.getQuantity()));
                        totalPrice = totalPrice.add(itemTotal);
                    }
                }
                // 行级购物车以 cartItemId 为键，价格等实时字段只更新返回值，不在这里覆盖 Redis 结构。
            }
        } catch (JsonProcessingException e) {
            log.error("获取购物车列表失败", e);
        }

        // 清理无效商品（可选）
        if (!invalidCartItemIds.isEmpty()) {
        hashOps.delete(invalidCartItemIds.toArray());

        if (isLoggedIn(userId)) {
            Map<Long, CartItemVO> removedItems = items.stream()
                    .filter(item -> "INVALID".equals(item.getStatus()))
                    .collect(Collectors.toMap(CartItemVO::getId, item -> item, (left, right) -> left, HashMap::new));
            for (String cartItemIdStr : invalidCartItemIds) {
                CartItemVO removedItem = removedItems.get(Long.valueOf(cartItemIdStr));
                cartChangeProducer.sendCartSaveMessage(buildDeleteMessage(userId, removedItem));
            }
        }
    }

    CartListVO listVO = new CartListVO();
        listVO.setItems(items);
        listVO.setTotalPrice(totalPrice);
        return listVO;
}

    @Override
    public void mergeCart(Long userId, String deviceId) {
        if (userId == null || userId <= 0 || deviceId == null || deviceId.isEmpty()) {
            return;
        }

        String tempKey = RedisKeyEnum.CART_TEMP.getKey(deviceId);
        String userKey = RedisKeyEnum.CART_USER.getKey(userId);

        BoundHashOperations<String, String, String> tempOps = stringRedisTemplate.boundHashOps(tempKey);
        BoundHashOperations<String, String, String> userOps = stringRedisTemplate.boundHashOps(userKey);

        try {
            hydrateCartFromDbIfNeeded(userId, deviceId, userOps);
            Map<String, String> tempEntries = tempOps.entries();
            if (tempEntries != null && !tempEntries.isEmpty()) {
                for (Map.Entry<String, String> entry : tempEntries.entrySet()) {
                    String tempJson = entry.getValue();
                    CartItemVO tempItem = readCartItem(tempJson);
                    tempItem.setBizType(resolveBizType(tempItem.getBizType()).getCode());
                    List<CartItemVO> matchedUserItems = findCartItems(userOps, tempItem.getSkuId(), tempItem.getShopId(), tempItem.getBizType());
                    CartItemVO finalItem = tempItem;

                    if (!matchedUserItems.isEmpty()) {
                        // 合并临时购物车与用户购物车时，同商品只保留一条记录。
                        finalItem = matchedUserItems.get(0);
                        int mergedQuantity = safeQuantity(finalItem.getQuantity()) + safeQuantity(tempItem.getQuantity());
                        boolean checked = Boolean.TRUE.equals(finalItem.getChecked()) || Boolean.TRUE.equals(tempItem.getChecked());
                        finalItem.setQuantity(mergedQuantity);
                        finalItem.setChecked(checked);
                    } else {
                        String cartItemField = getCartItemField(tempItem.getId());
                        if (Boolean.TRUE.equals(userOps.hasKey(cartItemField))) {
                            tempItem.setId(nextCartItemId());
                        }
                    }
                    writeCartItem(userOps, finalItem);

                    cartChangeProducer.sendCartSaveMessage(buildUpsertMessage(userId, finalItem));
                }
                // 删除临时购物车
                stringRedisTemplate.delete(tempKey);
            }
        } catch (JsonProcessingException e) {
            log.error("合并购物车失败", e);
        }
    }

    @Override
    public void clearInvalid(Long userId, String deviceId) {
        String cartKey = getCartKey(userId, deviceId);
        BoundHashOperations<String, String, String> hashOps = getCartOps(cartKey);

        try {
            Map<String, String> entries = hashOps.entries();
            if (entries != null) {
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    CartItemVO itemVO = readCartItem(entry.getValue());
                    if ("INVALID".equals(itemVO.getStatus())) {
                        hashOps.delete(entry.getKey());

                        if (isLoggedIn(userId)) {
                            cartChangeProducer.sendCartSaveMessage(buildDeleteMessage(userId, itemVO));
                        }
                    }
                }
            }

            // 如果清理后购物车为空，设置短期标记阻止回源
            Long size = hashOps.size();
            if (size == null || size == 0) {
                markCartAsEmpty(userId, deviceId);
            }
        } catch (JsonProcessingException e) {
            log.error("清理失效商品失败", e);
        }
    }
}
