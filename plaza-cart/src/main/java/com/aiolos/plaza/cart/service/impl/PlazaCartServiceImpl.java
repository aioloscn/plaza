package com.aiolos.plaza.cart.service.impl;

import com.aiolos.plaza.cart.model.bo.CartAddReq;
import com.aiolos.plaza.cart.model.bo.CartCheckReq;
import com.aiolos.plaza.cart.model.bo.CartUpdateReq;
import com.aiolos.plaza.cart.service.PlazaCartService;
import com.aiolos.plaza.cart.model.vo.CartItemVO;
import com.aiolos.plaza.cart.model.vo.CartListVO;
import com.aiolos.plaza.model.po.Product;
import com.aiolos.plaza.model.po.Shop;
import com.aiolos.plaza.service.CartItemService;
import com.aiolos.plaza.service.ProductService;
import com.aiolos.plaza.service.ShopService;
import com.aiolos.plaza.cart.mq.producer.CartSaveProducer;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.mq.message.CartAsyncSaveMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlazaCartServiceImpl implements PlazaCartService {

    @Resource
    private ProductService productService;

    @Resource
    private ShopService shopService;
    
    @Resource
    private CartItemService cartItemService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CartSaveProducer cartSaveProducer;

    private static final String CART_PREFIX = "cart:";
    // 购物车清空标记，用于防止幽灵数据回源，有效期较短（如1分钟）
    private static final String CART_EMPTY_MARK_PREFIX = "cart:empty_mark:";
    
    @Autowired
    private ObjectMapper objectMapper;

    private String getCartKey(Long userId, String deviceId) {
        if (userId != null && userId > 0) {
            return CART_PREFIX + "user:" + userId;
        } else if (deviceId != null && !deviceId.isEmpty()) {
            return CART_PREFIX + "temp:" + deviceId;
        }
        throw new RuntimeException("用户未登录且无设备ID");
    }

    private String getCartEmptyMarkKey(Long userId, String deviceId) {
        if (userId != null && userId > 0) {
            return CART_EMPTY_MARK_PREFIX + "user:" + userId;
        } else if (deviceId != null && !deviceId.isEmpty()) {
            return CART_EMPTY_MARK_PREFIX + "temp:" + deviceId;
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
        String productIdStr = String.valueOf(req.getProductId());

        try {
            if (Boolean.TRUE.equals(hashOps.hasKey(productIdStr))) {
                // 已存在，数量累加
                String json = hashOps.get(productIdStr);
                CartItemVO itemVO = objectMapper.readValue(json, CartItemVO.class);
                itemVO.setQuantity(itemVO.getQuantity() + req.getCount());
                hashOps.put(productIdStr, objectMapper.writeValueAsString(itemVO));
            } else {
                // 不存在，新增
                Product product = productService.getById(req.getProductId());
                if (product == null || product.getStatus() == 0) {
                    throw new RuntimeException("商品不存在或已下架");
                }
                Long shopId = req.getShopId() != null ? req.getShopId() : product.getShopId();
                Shop shop = shopService.getById(shopId);
                String shopName = shop != null ? shop.getName() : "未知店铺";
                
                CartItemVO itemVO = new CartItemVO();
                itemVO.setId(product.getId()); // Use product ID as item ID
                itemVO.setProductId(product.getId());
                itemVO.setShopId(shopId);
                itemVO.setShopName(shopName);
                itemVO.setProductName(product.getName());
                itemVO.setProductImage(product.getImageUrl());
                itemVO.setPrice(product.getPrice());
                itemVO.setQuantity(req.getCount());
                itemVO.setChecked(true);
                itemVO.setStock(product.getStock());
                itemVO.setStatus("VALID");

                hashOps.put(productIdStr, objectMapper.writeValueAsString(itemVO));
            }
            setCartExpire(cartKey, userId);
            
            // 异步落库到 MySQL
            if (userId != null && userId > 0) {
                CartAsyncSaveMessage message = new CartAsyncSaveMessage();
                message.setUserId(userId);
                // 重新获取一下最新的 itemVO，确保数据一致
                String json = hashOps.get(productIdStr);
                CartItemVO currentItem = objectMapper.readValue(json, CartItemVO.class);
                BeanUtils.copyProperties(currentItem, message);
                message.setChecked(currentItem.getChecked() ? 1 : 0);
                message.setPriceSnapshot(currentItem.getPrice());
                message.setStatus(1);
                
                cartSaveProducer.sendCartSaveMessage(message);
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
        String productIdStr = String.valueOf(req.getProductId());

        try {
            if (Boolean.TRUE.equals(hashOps.hasKey(productIdStr))) {
                String json = hashOps.get(productIdStr);
                CartItemVO itemVO = objectMapper.readValue(json, CartItemVO.class);
                itemVO.setQuantity(req.getCount());
                hashOps.put(productIdStr, objectMapper.writeValueAsString(itemVO));
                setCartExpire(cartKey, userId);

                // 异步落库到 MySQL
                if (userId != null && userId > 0) {
                    CartAsyncSaveMessage message = new CartAsyncSaveMessage();
                    message.setUserId(userId);
                    BeanUtils.copyProperties(itemVO, message);
                    message.setChecked(itemVO.getChecked() ? 1 : 0);
                    message.setPriceSnapshot(itemVO.getPrice());
                    message.setStatus(1);
                    cartSaveProducer.sendCartSaveMessage(message);
                }
            }
        } catch (JsonProcessingException e) {
            log.error("更新购物车数量失败", e);
            throw new RuntimeException("更新购物车数量失败");
        }
    }

    @Override
    public void deleteCartItem(Long userId, String deviceId, Long productId) {
        String cartKey = getCartKey(userId, deviceId);
        BoundHashOperations<String, String, String> hashOps = getCartOps(cartKey);
        hashOps.delete(String.valueOf(productId));
        
        // 如果删除后购物车为空，设置短期标记阻止回源
        Long size = hashOps.size();
        if (size == null || size == 0) {
            markCartAsEmpty(userId, deviceId);
        }

        // 异步删除 MySQL 中的记录
        if (userId != null && userId > 0) {
            CartAsyncSaveMessage message = new CartAsyncSaveMessage();
            message.setUserId(userId);
            message.setProductId(productId);
            message.setOperateType(2); // 2 表示删除
            cartSaveProducer.sendCartSaveMessage(message);
        }
    }

    @Override
    public void checkCartItem(Long userId, String deviceId, CartCheckReq req) {
        String cartKey = getCartKey(userId, deviceId);
        BoundHashOperations<String, String, String> hashOps = getCartOps(cartKey);

        try {
            if (req.getProductIds() != null && !req.getProductIds().isEmpty()) {
                for (Long pid : req.getProductIds()) {
                    String pidStr = String.valueOf(pid);
                    if (Boolean.TRUE.equals(hashOps.hasKey(pidStr))) {
                        String json = hashOps.get(pidStr);
                        CartItemVO itemVO = objectMapper.readValue(json, CartItemVO.class);
                        itemVO.setChecked(req.getChecked() == 1);
                        hashOps.put(pidStr, objectMapper.writeValueAsString(itemVO));
                    }
                }
            } else {
                // 全选/全不选
                Map<String, String> entries = hashOps.entries();
                if (entries != null) {
                    for (Map.Entry<String, String> entry : entries.entrySet()) {
                        CartItemVO itemVO = objectMapper.readValue(entry.getValue(), CartItemVO.class);
                        itemVO.setChecked(req.getChecked() == 1);
                        hashOps.put(entry.getKey(), objectMapper.writeValueAsString(itemVO));
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
        List<String> invalidProductIds = new ArrayList<>();
        
        try {
            Map<String, String> entries = hashOps.entries();
            // 如果 Redis 为空且用户已登录，尝试从 MySQL 加载数据
            if ((entries == null || entries.isEmpty()) && userId != null && userId > 0) {
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
                    // 获取商品最新信息
                    List<Long> pIds = dbItems.stream().map(CartItem::getProductId).collect(Collectors.toList());
                    List<Product> products = productService.listByIds(pIds);
                    Map<Long, Product> productMap = products.stream()
                        .collect(Collectors.toMap(Product::getId, p -> p));
                        
                    // 批量查询店铺信息
                    List<Long> shopIds = products.stream().map(Product::getShopId).distinct().collect(Collectors.toList());
                    Map<Long, String> shopNameMap = new java.util.HashMap<>();
                    if (!shopIds.isEmpty()) {
                        List<Shop> shops = shopService.listByIds(shopIds);
                        shopNameMap = shops.stream().collect(Collectors.toMap(Shop::getId, Shop::getName));
                    }
                        
                    for (CartItem dbItem : dbItems) {
                        Product product = productMap.get(dbItem.getProductId());
                        if (product != null) {
                            CartItemVO itemVO = new CartItemVO();
                            itemVO.setId(product.getId());
                            itemVO.setProductId(product.getId());
                            itemVO.setShopId(product.getShopId());
                            itemVO.setShopName(shopNameMap.getOrDefault(product.getShopId(), "未知店铺"));
                            itemVO.setProductName(product.getName());
                            itemVO.setProductImage(product.getImageUrl());
                            itemVO.setPrice(product.getPrice());
                            itemVO.setQuantity(dbItem.getQuantity());
                            itemVO.setChecked(dbItem.getChecked() == 1);
                            itemVO.setStock(product.getStock());
                            itemVO.setStatus(product.getStatus() == 1 ? "VALID" : "OFF_SHELF");
                            
                            // 回写 Redis
                            hashOps.put(String.valueOf(product.getId()), objectMapper.writeValueAsString(itemVO));
                            
                            // 添加到返回列表
                            items.add(itemVO);
                            if (Boolean.TRUE.equals(itemVO.getChecked()) && "VALID".equals(itemVO.getStatus())) {
                                BigDecimal itemTotal = itemVO.getPrice().multiply(new BigDecimal(itemVO.getQuantity()));
                                totalPrice = totalPrice.add(itemTotal);
                            }
                        }
                    }
                    // 重新读取 entries 避免下面逻辑重复处理（其实下面逻辑主要处理 redisItems，如果 entries 为空就不会进去了）
                    // 但为了逻辑统一，这里直接返回构建好的数据
                    CartListVO listVO = new CartListVO();
                    listVO.setItems(items);
                    listVO.setTotalPrice(totalPrice);
                    return listVO;
                }
            }
            
            if (entries != null && !entries.isEmpty()) {
                List<CartItemVO> redisItems = new ArrayList<>();
                List<Long> productIds = new ArrayList<>();
                
                // 1. 从 Redis 获取购物车数据
                for (String json : entries.values()) {
                    CartItemVO itemVO = objectMapper.readValue(json, CartItemVO.class);
                    redisItems.add(itemVO);
                    productIds.add(itemVO.getProductId());
                }
                
                // 2. 批量查询数据库获取最新价格、库存和状态
                List<Product> products = productService.listByIds(productIds);
                Map<Long, Product> productMap = products.stream()
                    .collect(Collectors.toMap(Product::getId, p -> p));

                for (CartItemVO item : redisItems) {
                    Product product = productMap.get(item.getProductId());
                    if (product == null) {
                        // 商品已物理删除
                        item.setStatus("INVALID");
                        invalidProductIds.add(String.valueOf(item.getProductId()));
                    } else {
                        // 更新最新信息
                        item.setProductName(product.getName());
                        item.setProductImage(product.getImageUrl());
                        item.setPrice(product.getPrice());
                        item.setStock(product.getStock());
                        
                        if (product.getStatus() == 0) {
                            item.setStatus("OFF_SHELF"); // 下架
                        } else if (product.getStock() < item.getQuantity()) {
                            item.setStatus("NO_STOCK"); // 库存不足
                        } else {
                            item.setStatus("VALID");
                        }
                    }
                    items.add(item);
                    
                    // 计算选中商品的总价（仅计算有效商品）
                    if (Boolean.TRUE.equals(item.getChecked()) && "VALID".equals(item.getStatus())) {
                        BigDecimal itemTotal = item.getPrice().multiply(new BigDecimal(item.getQuantity()));
                        totalPrice = totalPrice.add(itemTotal);
                    }
                }

                // 3. 异步更新 Redis 中的缓存数据（价格变动等）
                // 这里的更新策略可以灵活调整，例如仅在价格变动超过一定幅度或定时更新
                // 为简单起见，这里暂不回写 Redis，仅在下单时校验价格，或者由前端触发刷新
            }
        } catch (JsonProcessingException e) {
            log.error("获取购物车列表失败", e);
        }
        
        // 清理无效商品（可选）
        if (!invalidProductIds.isEmpty()) {
            hashOps.delete(invalidProductIds.toArray());
            
            // 同步删除 MySQL 中的记录
            if (userId != null && userId > 0) {
                for (String pidStr : invalidProductIds) {
                    CartAsyncSaveMessage message = new CartAsyncSaveMessage();
                    message.setUserId(userId);
                    message.setProductId(Long.valueOf(pidStr));
                    message.setOperateType(2); // 2 表示删除
                    cartSaveProducer.sendCartSaveMessage(message);
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
        
        String tempKey = CART_PREFIX + "temp:" + deviceId;
        String userKey = CART_PREFIX + "user:" + userId;
        
        BoundHashOperations<String, String, String> tempOps = stringRedisTemplate.boundHashOps(tempKey);
        BoundHashOperations<String, String, String> userOps = stringRedisTemplate.boundHashOps(userKey);
        
        try {
            Map<String, String> tempEntries = tempOps.entries();
            if (tempEntries != null && !tempEntries.isEmpty()) {
                for (Map.Entry<String, String> entry : tempEntries.entrySet()) {
                    String productId = entry.getKey();
                    String tempJson = entry.getValue();
                    CartItemVO tempItem = objectMapper.readValue(tempJson, CartItemVO.class);
                    CartItemVO finalItem;
                    
                    if (Boolean.TRUE.equals(userOps.hasKey(productId))) {
                        // 累加
                        String userJson = userOps.get(productId);
                        CartItemVO userItem = objectMapper.readValue(userJson, CartItemVO.class);
                        userItem.setQuantity(userItem.getQuantity() + tempItem.getQuantity());
                        userOps.put(productId, objectMapper.writeValueAsString(userItem));
                        finalItem = userItem;
                    } else {
                        // 直接添加
                        userOps.put(productId, tempJson);
                        finalItem = tempItem;
                    }
                    
                    // 异步落库到 MySQL
                    CartAsyncSaveMessage message = new CartAsyncSaveMessage();
                    BeanUtils.copyProperties(finalItem, message);
                    message.setUserId(userId);
                    message.setChecked(finalItem.getChecked() ? 1 : 0);
                    message.setPriceSnapshot(finalItem.getPrice());
                    message.setStatus(1);
                    cartSaveProducer.sendCartSaveMessage(message);
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
                    CartItemVO itemVO = objectMapper.readValue(entry.getValue(), CartItemVO.class);
                    if ("INVALID".equals(itemVO.getStatus())) {
                        hashOps.delete(entry.getKey());
                        
                        // 异步删除 MySQL 中的记录
                        if (userId != null && userId > 0) {
                            CartAsyncSaveMessage message = new CartAsyncSaveMessage();
                            message.setUserId(userId);
                            message.setProductId(itemVO.getProductId());
                            message.setOperateType(2); // 2 表示删除
                            cartSaveProducer.sendCartSaveMessage(message);
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