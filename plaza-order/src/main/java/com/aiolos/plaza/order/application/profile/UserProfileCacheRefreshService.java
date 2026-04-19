package com.aiolos.plaza.order.application.profile;

import cn.hutool.core.collection.CollectionUtil;
import com.aiolos.plaza.enums.OrderPaymentStatus;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.model.dto.UserShopProfileCacheDTO;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ParentOrder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 支付成功后刷新 plaza-home 用户画像缓存
 * 只更新 Redis，不回写画像快照表，夜间 20:00 全量任务负责统一重建并落库
 */
@Slf4j
@Service
public class UserProfileCacheRefreshService {

    private static final int PROFILE_MAX_SHOP_COUNT = 6;
    private static final int PROFILE_MAX_CATEGORY_COUNT = 8;
    private static final int PROFILE_MAX_ORDER_COUNT = 50;
    private static final int PROFILE_MIN_PRICE_TOLERANCE = 8;
    private static final int PROFILE_MAX_PRICE_TOLERANCE = 120;
    private static final int PROFILE_RECENT_DAYS = 7;
    private static final double PROFILE_RECENCY_DECAY_DAYS = 15D;
    private static final double PROFILE_AMOUNT_LOG_BASE = 2D;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate shopRedisTemplate;

    public UserProfileCacheRefreshService(OrderMapper orderMapper,
                                          OrderItemMapper orderItemMapper,
                                          ObjectMapper objectMapper,
                                          @Qualifier("shopRedisTemplate") StringRedisTemplate shopRedisTemplate) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.objectMapper = objectMapper;
        this.shopRedisTemplate = shopRedisTemplate;
    }

    /**
     * 支付成功后基于本次父订单增量刷新用户画像缓存
     */
    public void refreshAfterPaid(ParentOrder parentOrder) {
        if (parentOrder == null || parentOrder.getUserId() == null || StringUtils.isBlank(parentOrder.getParentOrderSn())) {
            return;
        }

        List<Order> paidOrders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .select(Order::getId, Order::getUserId, Order::getShopId, Order::getPayAmount, Order::getCreateTime, Order::getPaymentStatus, Order::getParentOrderSn)
                .eq(Order::getParentOrderSn, parentOrder.getParentOrderSn())
                .eq(Order::getDeleteStatus, 0)
                .eq(Order::getPaymentStatus, OrderPaymentStatus.PAID.getCode()));
        if (CollectionUtil.isEmpty(paidOrders)) {
            log.warn("支付成功后刷新画像缓存未找到已支付子订单, parentOrderSn={}", parentOrder.getParentOrderSn());
            return;
        }

        List<Long> orderIds = paidOrders.stream()
                .map(Order::getId)
                .filter(Objects::nonNull)
                .toList();
        List<OrderItem> orderItems = CollectionUtil.isEmpty(orderIds)
                ? Collections.emptyList()
                : orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .select(OrderItem::getOrderId, OrderItem::getProductCategoryId, OrderItem::getProductPrice, OrderItem::getProductQuantity)
                .in(OrderItem::getOrderId, orderIds));

        String key = RedisKeyEnum.HOME_USER_PROFILE.getKey(parentOrder.getUserId());
        UserShopProfileCacheDTO profile = readProfile(key, parentOrder.getUserId());
        bootstrapRawFieldsIfNeeded(profile);
        // 增量累加 raw 聚合字段，再统一重算派生画像字段，避免分散更新导致不一致
        mergePaidOrders(profile, paidOrders, orderItems);
        recalculateDerivedFields(profile);
        writeProfile(key, profile);
    }

    private UserShopProfileCacheDTO readProfile(String key, Long userId) {
        String cache = shopRedisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(cache)) {
            // 首次画像或缓存过期，先返回空壳画像并按本次支付单增量构建
            UserShopProfileCacheDTO profile = new UserShopProfileCacheDTO();
            profile.setUserId(userId);
            return profile;
        }
        try {
            UserShopProfileCacheDTO profile = objectMapper.readValue(cache, UserShopProfileCacheDTO.class);
            if (profile.getUserId() == null) {
                profile.setUserId(userId);
            }
            return profile;
        } catch (Exception e) {
            log.warn("支付成功后反序列化用户画像缓存失败，回退空画像, key={}", key, e);
            UserShopProfileCacheDTO profile = new UserShopProfileCacheDTO();
            profile.setUserId(userId);
            return profile;
        }
    }

    /**
     * 兼容旧缓存结构
     * 老缓存只有派生结果，没有 raw 字段时，用当前派生值做一次近似初始化，避免增量刷新丢失全部历史画像
     */
    private void bootstrapRawFieldsIfNeeded(UserShopProfileCacheDTO profile) {
        if ((profile.getShopStrengthRaw() == null || profile.getShopStrengthRaw().isEmpty())
                && profile.getShopPreference() != null && !profile.getShopPreference().isEmpty()) {
            profile.setShopStrengthRaw(new LinkedHashMap<>(profile.getShopPreference()));
        }
        if ((profile.getCategoryStrengthRaw() == null || profile.getCategoryStrengthRaw().isEmpty())
                && profile.getCategoryPreference() != null && !profile.getCategoryPreference().isEmpty()) {
            profile.setCategoryStrengthRaw(new LinkedHashMap<>(profile.getCategoryPreference()));
        }
        if ((profile.getPayWeightTotalRaw() == null || profile.getPayWeightTotalRaw() <= 0)
                && profile.getAvgPriceLevel() != null && profile.getAvgPriceLevel() > 0) {
            profile.setPayWeightTotalRaw(1D);
            profile.setPayWeightedSumRaw(BigDecimal.valueOf(profile.getAvgPriceLevel()));
        }
        if ((profile.getPriceWeightedFactorRaw() == null || profile.getPriceWeightedFactorRaw() <= 0)
                && profile.getAvgPriceLevel() != null && profile.getAvgPriceLevel() > 0) {
            BigDecimal avgPrice = BigDecimal.valueOf(profile.getAvgPriceLevel());
            profile.setPriceWeightedFactorRaw(1D);
            profile.setPriceWeightedSumRaw(avgPrice);
            profile.setPriceWeightedSquareSumRaw(avgPrice.multiply(avgPrice));
        }
    }

    private void mergePaidOrders(UserShopProfileCacheDTO profile, List<Order> paidOrders, List<OrderItem> orderItems) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime recentStart = now.minusDays(PROFILE_RECENT_DAYS);
        Map<Long, Double> orderRecency = new HashMap<>();
        Map<Long, Double> orderBehaviorWeight = new HashMap<>();
        // 以历史 shopStrengthRaw 的 key 作为“老店”集合，区分 recentNew 与 recentActive
        Set<Long> historicalShopIds = new LinkedHashSet<>(ensureShopStrengthRaw(profile).keySet());

        for (Order order : paidOrders) {
            if (order.getId() == null) {
                continue;
            }
            double recencyWeight = recencyWeight(order.getCreateTime(), now);
            orderRecency.put(order.getId(), recencyWeight);
            double behaviorWeight = recencyWeight * amountWeight(order.getPayAmount());
            orderBehaviorWeight.put(order.getId(), behaviorWeight);
            profile.setPaidOrderCountRaw(defaultInt(profile.getPaidOrderCountRaw()) + 1);
            if (order.getCreateTime() != null && !order.getCreateTime().isBefore(recentStart)) {
                profile.setRecentPaidOrderCountRaw(defaultInt(profile.getRecentPaidOrderCountRaw()) + 1);
            }

            if (order.getShopId() != null) {
                ensureShopStrengthRaw(profile).merge(order.getShopId(), behaviorWeight, Double::sum);
                if (order.getCreateTime() != null && !order.getCreateTime().isBefore(recentStart)) {
                    // 近 7 天命中时：历史出现过归 recentActive，否则归 recentNew
                    if (historicalShopIds.contains(order.getShopId())) {
                        ensureRecentShopStrengthRaw(profile).merge(order.getShopId(), behaviorWeight, Double::sum);
                    } else {
                        ensureRecentNewShopStrengthRaw(profile).merge(order.getShopId(), behaviorWeight, Double::sum);
                    }
                }
            }

            if (order.getPayAmount() != null && order.getPayAmount().compareTo(BigDecimal.ZERO) > 0) {
                profile.setPayWeightedSumRaw(safeDecimal(profile.getPayWeightedSumRaw())
                        .add(order.getPayAmount().multiply(BigDecimal.valueOf(recencyWeight))));
                profile.setPayWeightTotalRaw(safeDouble(profile.getPayWeightTotalRaw()) + recencyWeight);
            }
        }

        Map<Long, Order> orderIndex = paidOrders.stream()
                .filter(order -> order.getId() != null)
                .collect(Collectors.toMap(Order::getId, order -> order, (a, b) -> a));
        for (OrderItem orderItem : orderItems) {
            Order sourceOrder = orderIndex.get(orderItem.getOrderId());
            if (sourceOrder == null) {
                continue;
            }
            double recencyWeight = orderRecency.getOrDefault(sourceOrder.getId(), recencyWeight(sourceOrder.getCreateTime(), now));
            long quantity = Math.max(orderItem.getProductQuantity() == null ? 1L : orderItem.getProductQuantity(), 1L);
            double quantityWeight = Math.sqrt(quantity);
            BigDecimal productPrice = orderItem.getProductPrice();
            double spendWeight = amountWeight(productPrice == null
                    ? sourceOrder.getPayAmount()
                    : productPrice.multiply(BigDecimal.valueOf(quantity)));
            double itemWeight = recencyWeight * quantityWeight * spendWeight;

            if (orderItem.getProductCategoryId() != null) {
                ensureCategoryStrengthRaw(profile).merge(orderItem.getProductCategoryId(), itemWeight, Double::sum);
            }

            profile.setPaidItemCountRaw(defaultInt(profile.getPaidItemCountRaw()) + 1);
            if (productPrice == null || productPrice.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal weight = BigDecimal.valueOf(recencyWeight).multiply(BigDecimal.valueOf(quantity));
            profile.setPriceWeightedSumRaw(safeDecimal(profile.getPriceWeightedSumRaw()).add(productPrice.multiply(weight)));
            profile.setPriceWeightedSquareSumRaw(safeDecimal(profile.getPriceWeightedSquareSumRaw())
                    .add(productPrice.multiply(productPrice).multiply(weight)));
            profile.setPriceWeightedFactorRaw(safeDouble(profile.getPriceWeightedFactorRaw()) + recencyWeight * quantity);
        }
    }

    private void recalculateDerivedFields(UserShopProfileCacheDTO profile) {
        Map<Long, Double> shopStrength = ensureShopStrengthRaw(profile);
        Map<Long, Double> recentShopStrength = ensureRecentShopStrengthRaw(profile);
        Map<Long, Double> recentNewShopStrength = ensureRecentNewShopStrengthRaw(profile);
        Map<Long, Double> categoryStrength = ensureCategoryStrengthRaw(profile);

        List<Long> favoriteShopIds = topNKeysByScore(shopStrength, PROFILE_MAX_SHOP_COUNT);
        Set<Long> favoriteShopIdSet = new LinkedHashSet<>(favoriteShopIds);
        List<Long> recentNewShopIds = topNKeysByScore(excludeKeys(recentNewShopStrength, favoriteShopIdSet), PROFILE_MAX_SHOP_COUNT);
        Set<Long> recentNewShopIdSet = new LinkedHashSet<>(recentNewShopIds);
        List<Long> recentActiveShopIds = topNKeysByScore(
                excludeKeys(recentShopStrength, unionSet(favoriteShopIdSet, recentNewShopIdSet)),
                PROFILE_MAX_SHOP_COUNT
        );

        // 三个店铺维度保持互斥，避免同一 shopId 在多个画像维度叠加加权
        profile.setFavoriteShopIds(favoriteShopIds);
        profile.setRecentNewShopIds(recentNewShopIds);
        profile.setRecentActiveShopIds(recentActiveShopIds);
        profile.setShopPreference(normalizePreference(shopStrength, PROFILE_MAX_SHOP_COUNT));
        profile.setFavoriteCategoryIds(topNKeysByScore(categoryStrength, PROFILE_MAX_CATEGORY_COUNT));
        profile.setCategoryPreference(normalizePreference(categoryStrength, PROFILE_MAX_CATEGORY_COUNT));

        double payWeightTotal = safeDouble(profile.getPayWeightTotalRaw());
        if (payWeightTotal > 0) {
            profile.setAvgPriceLevel(safeDecimal(profile.getPayWeightedSumRaw())
                    .divide(BigDecimal.valueOf(payWeightTotal), 0, RoundingMode.HALF_UP)
                    .intValue());
        }

        double weightedPriceFactor = safeDouble(profile.getPriceWeightedFactorRaw());
        if (weightedPriceFactor > 0) {
            double avgPrice = safeDecimal(profile.getPriceWeightedSumRaw())
                    .divide(BigDecimal.valueOf(weightedPriceFactor), 4, RoundingMode.HALF_UP)
                    .doubleValue();
            double meanSquare = safeDecimal(profile.getPriceWeightedSquareSumRaw())
                    .divide(BigDecimal.valueOf(weightedPriceFactor), 4, RoundingMode.HALF_UP)
                    .doubleValue();
            double variance = Math.max(0D, meanSquare - avgPrice * avgPrice);
            int avgPriceInt = (int) Math.round(avgPrice);
            int tolerance = (int) Math.round(Math.sqrt(variance) * 1.6D);
            tolerance = Math.max(PROFILE_MIN_PRICE_TOLERANCE, Math.min(tolerance, PROFILE_MAX_PRICE_TOLERANCE));
            profile.setAvgPriceLevel(avgPriceInt);
            profile.setPriceTolerance(tolerance);
            profile.setPriceLowerBound(Math.max(1, avgPriceInt - tolerance));
            profile.setPriceUpperBound(avgPriceInt + tolerance);
        } else if (profile.getAvgPriceLevel() != null) {
            int fallbackTolerance = Math.max(PROFILE_MIN_PRICE_TOLERANCE, profile.getAvgPriceLevel() / 3);
            profile.setPriceTolerance(Math.min(fallbackTolerance, PROFILE_MAX_PRICE_TOLERANCE));
            profile.setPriceLowerBound(Math.max(1, profile.getAvgPriceLevel() - profile.getPriceTolerance()));
            profile.setPriceUpperBound(profile.getAvgPriceLevel() + profile.getPriceTolerance());
        }

        double recentRatio = defaultInt(profile.getRecentPaidOrderCountRaw()) * 1.0 / Math.max(defaultInt(profile.getPaidOrderCountRaw()), 1);
        double orderCoverage = Math.log1p(defaultInt(profile.getPaidOrderCountRaw())) / Math.log(1 + PROFILE_MAX_ORDER_COUNT);
        double itemCoverage = Math.log1p(defaultInt(profile.getPaidItemCountRaw())) / Math.log(1 + PROFILE_MAX_ORDER_COUNT * 4.0);
        double confidence = 0.45 * orderCoverage + 0.35 * itemCoverage + 0.20 * recentRatio;
        profile.setProfileConfidence(round4(Math.max(0.1D, Math.min(confidence, 1D))));
    }

    private void writeProfile(String key, UserShopProfileCacheDTO profile) {
        try {
            long expire = RedisKeyEnum.HOME_USER_PROFILE.getDefaultExpireSeconds();
            // 与 plaza-home 查询链路共享同一 Redis Key，支付后立即可见
            shopRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(profile), expire, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("支付成功后写入用户画像缓存失败", e);
        }
    }

    private Map<Long, Double> ensureShopStrengthRaw(UserShopProfileCacheDTO profile) {
        if (profile.getShopStrengthRaw() == null) {
            profile.setShopStrengthRaw(new LinkedHashMap<>());
        }
        return profile.getShopStrengthRaw();
    }

    private Map<Long, Double> ensureRecentShopStrengthRaw(UserShopProfileCacheDTO profile) {
        if (profile.getRecentShopStrengthRaw() == null) {
            profile.setRecentShopStrengthRaw(new LinkedHashMap<>());
        }
        return profile.getRecentShopStrengthRaw();
    }

    private Map<Long, Double> ensureRecentNewShopStrengthRaw(UserShopProfileCacheDTO profile) {
        if (profile.getRecentNewShopStrengthRaw() == null) {
            profile.setRecentNewShopStrengthRaw(new LinkedHashMap<>());
        }
        return profile.getRecentNewShopStrengthRaw();
    }

    private Map<Long, Double> ensureCategoryStrengthRaw(UserShopProfileCacheDTO profile) {
        if (profile.getCategoryStrengthRaw() == null) {
            profile.setCategoryStrengthRaw(new LinkedHashMap<>());
        }
        return profile.getCategoryStrengthRaw();
    }

    private BigDecimal safeDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private double safeDouble(Double value) {
        return value == null ? 0D : value;
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private Map<Long, Double> normalizePreference(Map<Long, Double> scoreMap, int maxCount) {
        if (scoreMap.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map.Entry<Long, Double>> sorted = scoreMap.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(maxCount)
                .toList();
        if (CollectionUtil.isEmpty(sorted)) {
            return Collections.emptyMap();
        }
        double peak = sorted.get(0).getValue();
        if (peak <= 0) {
            return Collections.emptyMap();
        }
        Map<Long, Double> result = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> entry : sorted) {
            result.put(entry.getKey(), round4(entry.getValue() / peak));
        }
        return result;
    }

    private List<Long> topNKeysByScore(Map<Long, Double> scoreMap, int n) {
        if (scoreMap.isEmpty()) {
            return Collections.emptyList();
        }
        return scoreMap.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<Long, Double> excludeKeys(Map<Long, Double> scoreMap, Set<Long> excludedKeys) {
        if (scoreMap.isEmpty()) {
            return Collections.emptyMap();
        }
        if (CollectionUtil.isEmpty(excludedKeys)) {
            return scoreMap;
        }
        Map<Long, Double> result = new LinkedHashMap<>();
        scoreMap.forEach((key, value) -> {
            if (key == null || excludedKeys.contains(key)) {
                return;
            }
            result.put(key, value);
        });
        return result;
    }

    private Set<Long> unionSet(Set<Long> left, Set<Long> right) {
        Set<Long> result = new LinkedHashSet<>();
        if (CollectionUtil.isNotEmpty(left)) {
            result.addAll(left);
        }
        if (CollectionUtil.isNotEmpty(right)) {
            result.addAll(right);
        }
        return result;
    }

    private double recencyWeight(LocalDateTime eventTime, LocalDateTime now) {
        if (eventTime == null) {
            return 0.2D;
        }
        long ageDays = Math.max(Duration.between(eventTime, now).toDays(), 0L);
        return Math.exp(-ageDays / PROFILE_RECENCY_DECAY_DAYS);
    }

    private double amountWeight(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return 1D;
        }
        return 0.8D + Math.log1p(amount.doubleValue()) / PROFILE_AMOUNT_LOG_BASE;
    }

    private double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
