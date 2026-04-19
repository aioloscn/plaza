package com.aiolos.plaza.home.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.common.model.ContextInfo;
import com.aiolos.common.wrapper.PageModel;
import com.aiolos.common.wrapper.PageResult;
import com.aiolos.plaza.enums.OrderPaymentStatus;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.exceptions.HomeExceptionEnum;
import com.aiolos.plaza.home.config.UserProfileScoreConfig;
import com.aiolos.plaza.home.model.bo.RecommendShopBO;
import com.aiolos.plaza.home.model.bo.SearchShopBO;
import com.aiolos.plaza.home.model.bo.UserProfileSearchShopBO;
import com.aiolos.plaza.home.model.profile.ShopSearchBusinessBoostProfile;
import com.aiolos.plaza.home.model.profile.UserShopProfile;
import com.aiolos.plaza.home.model.vo.RecommendShopVO;
import com.aiolos.plaza.home.service.HomeShopService;
import com.aiolos.plaza.home.service.UserProfileShopSearchService;
import com.aiolos.plaza.mapper.OrderItemMapper;
import com.aiolos.plaza.mapper.OrderMapper;
import com.aiolos.plaza.mapper.ShopSearchBoostConfigMapper;
import com.aiolos.plaza.mapper.UserShopProfileSnapshotMapper;
import com.aiolos.plaza.model.po.Order;
import com.aiolos.plaza.model.po.OrderItem;
import com.aiolos.plaza.model.po.ShopSearchBoostConfig;
import com.aiolos.plaza.model.po.UserShopProfileSnapshot;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 用户画像门店搜索实现
 * 步骤：加载/计算用户画像 -> 构建 ES function_score/script_score 查询 -> 解析并返回
 */
@Slf4j
@Service
@AllArgsConstructor
public class UserProfileShopSearchServiceImpl implements UserProfileShopSearchService {

    /**
     * 用户画像窗口期天数
     * 仅统计最近 30 天已支付订单，平衡画像稳定性与时效性
     */
    private static final int PROFILE_ORDER_DAYS = 30;
    /**
     * 近期活跃窗口期天数
     * 用于识别近期偏好店铺，避免历史强偏好长期固化
     */
    private static final int PROFILE_RECENT_DAYS = 7;
    /**
     * 单用户画像构建时最多拉取的订单数
     * 控制数据库扫描量并限制单次构建耗时
     */
    private static final int PROFILE_MAX_ORDER_COUNT = 50;
    /**
     * 保留的偏好店铺数量上限
     */
    private static final int PROFILE_MAX_SHOP_COUNT = 6;
    /**
     * 保留的类目偏好数量上限
     */
    private static final int PROFILE_MAX_CATEGORY_COUNT = 8;
    /**
     * 批量画像任务单次最大刷新用户数
     */
    private static final int PROFILE_BATCH_REBUILD_LIMIT = 2000;
    /**
     * ES 脚本参与计算的店铺 ID 数量上限
     * 防止 terms/script 参数过长影响检索性能
     */
    private static final int SCRIPT_SHOP_ID_LIMIT = 100;
    /**
     * 价格容忍度下限
     * 避免样本过于集中导致价格过滤过窄
     */
    private static final int PROFILE_MIN_PRICE_TOLERANCE = 8;
    /**
     * 价格容忍度上限
     * 避免价格区间过宽导致画像失真
     */
    private static final int PROFILE_MAX_PRICE_TOLERANCE = 120;
    /**
     * 行为时间衰减半径参数（天）
     * 值越小越偏向近期行为，值越大越重视长期历史
     */
    private static final double PROFILE_RECENCY_DECAY_DAYS = 15D;
    /**
     * 消费金额权重对数压缩基数
     * 用于抑制极端大额消费对画像权重的放大效应
     */
    private static final double PROFILE_AMOUNT_LOG_BASE = 2D;

    private static final String ES_GRAY_HEADER = "X-Es-Profile-Gray";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ShopSearchBoostConfigMapper shopSearchBoostConfigMapper;
    private final UserShopProfileSnapshotMapper userShopProfileSnapshotMapper;
    private final HomeShopService homeShopService;
    private final UserProfileScoreConfig userProfileScoreConfig;

    /**
     * 首页推荐统一入口
     * 命中灰度走新版画像ES，未命中回退旧版推荐
     *
     * @param model 推荐分页请求
     * @return 门店推荐分页结果
     */
    @Override
    public PageResult<RecommendShopVO> recommendES(PageModel<RecommendShopBO> model) {
        if (!shouldUseUserProfileQuery()) {
            return homeShopService.recommend(model);
        }
        log.info("userId: {} 命中灰度recommendES", ContextInfo.getUserId());

        UserProfileSearchShopBO req = convertRecommendToUserProfileRequest(model.getData());
        PageResult<RecommendShopVO> pageResult = new PageResult<>();
        pageResult.setCurrent(model.getCurrent());
        pageResult.setSize(model.getSize());

        Long userId = ContextInfo.getUserId();
        UserShopProfile profile = getProfile(req, userId);
        String queryJson = buildESQuery(req, profile, model.getCurrent(), model.getSize());
        log.info(queryJson);
        try {
            Request request = new Request("GET", "/shop/_search");
            request.setJsonEntity(queryJson);
            Response response = restClient.performRequest(request);
            String responseBody = new String(response.getEntity().getContent().readAllBytes());
            return parseESResult(responseBody, pageResult);
        } catch (IOException e) {
            log.error("首页推荐画像ES检索失败, req={}, userId={}", req, userId, e);
            ExceptionUtil.throwException(HomeExceptionEnum.HOME_ES_QUERY_FAIL);
            return pageResult;
        }
    }
    
    /**
     * 搜索主流程
     * 使用当前登录用户构建画像并参与检索打分
     * @param model 搜索分页请求，包含坐标、关键词和画像开关
     * @return 门店推荐分页结果
     */
    @Override
    public PageResult<RecommendShopVO> searchES(PageModel<UserProfileSearchShopBO> model) {
        // 空参保护，保持与控制层一致
        UserProfileSearchShopBO req = model.getData();
        PageResult<RecommendShopVO> pageResult = new PageResult<>();
        pageResult.setCurrent(model.getCurrent());
        pageResult.setSize(model.getSize());

        Long userId = ContextInfo.getUserId();
        if (!shouldUseUserProfileQuery()) {
            return homeShopService.searchES(convertLegacyModel(model));
        }
        log.info("userId: {} 命中灰度searchES", ContextInfo.getUserId());

        UserShopProfile profile = getProfile(req, userId);
        String queryJson = buildESQuery(req, profile, model.getCurrent(), model.getSize());
        log.info(queryJson);
        try {
            Request request = new Request("GET", "/shop/_search");
            request.setJsonEntity(queryJson);
            Response response = restClient.performRequest(request);
            String responseBody = new String(response.getEntity().getContent().readAllBytes());
            return parseESResult(responseBody, pageResult);
        } catch (IOException e) {
            log.error("用户画像ES检索失败, req={}, userId={}", req, userId, e);
            ExceptionUtil.throwException(HomeExceptionEnum.HOME_ES_QUERY_FAIL);
            return pageResult; // 理论不会到达，这里只是兜底
        }
    }

    /**
     * 是否命中灰度策略
     * 只读取网关注入的灰度结果
     * @return true 表示走画像检索，false 表示降级到旧链路
     */
    private boolean shouldUseUserProfileQuery() {
        Boolean gatewayDecision = resolveGatewayGrayHeader();
        return Boolean.TRUE.equals(gatewayDecision);
    }

    /**
     * 优先读取网关注入的灰度决策
     * 1/true 表示命中新版，0/false 表示走旧版
     * @return true 命中新版，false 走旧版，null 表示无有效灰度头
     */
    private Boolean resolveGatewayGrayHeader() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null || attrs.getRequest() == null) {
            return null;
        }
        String header = attrs.getRequest().getHeader(ES_GRAY_HEADER);
        if (StringUtils.isBlank(header)) {
            return null;
        }
        String value = header.trim().toLowerCase();
        if ("1".equals(value) || "true".equals(value)) {
            return true;
        }
        if ("0".equals(value) || "false".equals(value)) {
            return false;
        }
        return null;
    }

    /**
     * 把新版入参转换为旧版 ES 入参
     * @param model 新版画像搜索入参
     * @return 旧版搜索入参
     */
    private PageModel<SearchShopBO> convertLegacyModel(PageModel<UserProfileSearchShopBO> model) {
        SearchShopBO legacyReq = new SearchShopBO();
        legacyReq.setLongitude(model.getData().getLongitude());
        legacyReq.setLatitude(model.getData().getLatitude());
        legacyReq.setKeyword(model.getData().getKeyword());
        legacyReq.setCategoryId(model.getData().getCategoryId());
        legacyReq.setTag(model.getData().getTag());
        legacyReq.setOrderBy(model.getData().getOrderBy());

        PageModel<SearchShopBO> legacyModel = new PageModel<>();
        legacyModel.setCurrent(model.getCurrent());
        legacyModel.setSize(model.getSize());
        legacyModel.setData(legacyReq);
        return legacyModel;
    }

    /**
     * 把首页推荐入参转换为画像ES入参
     *
     * @param data 首页推荐入参
     * @return 画像搜索入参
     */
    private UserProfileSearchShopBO convertRecommendToUserProfileRequest(RecommendShopBO data) {
        UserProfileSearchShopBO req = new UserProfileSearchShopBO();
        if (data != null) {
            req.setLongitude(data.getLongitude());
            req.setLatitude(data.getLatitude());
        }
        req.setOrderBy(0);
        req.setProfileEnabled(true);
        return req;
    }

    /**
     * 获取用户画像
     * 查询链路只读缓存，不在请求线程回源构建
     * @param req 搜索请求
     * @param userId 当前登录用户 ID
     */
    private UserShopProfile getProfile(UserProfileSearchShopBO req, Long userId) {
        if (userId == null || Boolean.FALSE.equals(req.getProfileEnabled())) {
            return new UserShopProfile();
        }

        String key = RedisKeyEnum.HOME_USER_PROFILE.getKey(userId);
        String cache = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isNotBlank(cache)) {
            try {
                return objectMapper.readValue(cache, UserShopProfile.class);
            } catch (JsonProcessingException e) {
                log.warn("用户画像缓存反序列化失败, key={}", key, e);
                stringRedisTemplate.delete(key);
            }
        }
        UserShopProfile snapshotProfile = getProfileFromSnapshot(userId);
        if (snapshotProfile != null) {
            saveProfileToCache(snapshotProfile);
            return snapshotProfile;
        }
        log.debug("用户画像缓存和快照表均未命中，返回空画像等待定时任务构建, userId={}", userId);
        UserShopProfile profile = new UserShopProfile();
        profile.setUserId(userId);
        return profile;
    }

    /**
     * 批量刷新最近活跃用户画像缓存
     * @param lookbackDays 回看天数
     * @param maxUsers 最大刷新用户数
     * @return 实际刷新成功的用户数量
     */
    public int rebuildRecentUserProfileCache(int lookbackDays, int maxUsers) {
        int finalLookbackDays = Math.max(lookbackDays, 1);
        int finalMaxUsers = Math.min(Math.max(maxUsers, 1), PROFILE_BATCH_REBUILD_LIMIT);
        LocalDateTime start = LocalDateTime.now().minusDays(finalLookbackDays);

        int fetchLimit = Math.min(finalMaxUsers * 5, PROFILE_BATCH_REBUILD_LIMIT * 5);
        LambdaQueryWrapper<Order> orderQuery = new LambdaQueryWrapper<>();
        orderQuery.select(Order::getUserId, Order::getCreateTime);
        orderQuery.eq(Order::getDeleteStatus, 0);
        orderQuery.eq(Order::getPaymentStatus, OrderPaymentStatus.PAID.getCode());
        orderQuery.ge(Order::getCreateTime, start);
        orderQuery.isNotNull(Order::getUserId);
        orderQuery.orderByDesc(Order::getCreateTime);
        orderQuery.last("limit " + fetchLimit);

        List<Order> recentOrders = orderMapper.selectList(orderQuery);
        if (CollectionUtil.isEmpty(recentOrders)) {
            return 0;
        }

        List<Long> userIds = recentOrders.stream()
                .map(Order::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .limit(finalMaxUsers)
                .toList();
        if (CollectionUtil.isEmpty(userIds)) {
            return 0;
        }

        int refreshCount = 0;
        for (Long userId : userIds) {
            try {
                rebuildProfileCache(userId);
                refreshCount++;
            } catch (Exception e) {
                log.error("刷新用户画像缓存失败, userId={}", userId, e);
            }
        }
        return refreshCount;
    }

    /**
     * 刷新单个用户画像缓存
     * 由定时任务或运维补偿调用，不在查询主链路执行
     * @param userId 目标用户 ID
     */
    public void rebuildProfileCache(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        UserShopProfile profile = buildProfileFromDB(userId);
        if (profile.getUserId() == null) {
            profile.setUserId(userId);
        }
        saveProfileSnapshot(profile);
        saveProfileToCache(profile);
    }

    /**
     * 从订单数据聚合用户画像
     * 包含偏好店铺、近期活跃店铺、类目偏好和价格带
     * @param userId 目标用户 ID
     * @return 聚合后的用户画像
     */
    private UserShopProfile buildProfileFromDB(Long userId) {
        UserShopProfile profile = new UserShopProfile();
        profile.setUserId(userId);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusDays(PROFILE_ORDER_DAYS);
        LocalDateTime recentStart = now.minusDays(PROFILE_RECENT_DAYS);
        LambdaQueryWrapper<Order> orderQuery = new LambdaQueryWrapper<>();
        orderQuery.select(Order::getId, Order::getShopId, Order::getPayAmount, Order::getCreateTime);
        orderQuery.eq(Order::getUserId, userId);
        orderQuery.eq(Order::getDeleteStatus, 0);
        orderQuery.eq(Order::getPaymentStatus, OrderPaymentStatus.PAID.getCode());
        orderQuery.ge(Order::getCreateTime, start);
        orderQuery.orderByDesc(Order::getCreateTime);
        orderQuery.last("limit " + PROFILE_MAX_ORDER_COUNT);
        List<Order> orders = orderMapper.selectList(orderQuery);
        if (CollectionUtil.isEmpty(orders)) {
            return profile;
        }

        Map<Long, Order> orderIndex = orders.stream()
                .filter(order -> order.getId() != null)
                .collect(Collectors.toMap(Order::getId, order -> order, (a, b) -> a));

        // 记录订单级时间衰减，后续 item 聚合时继续复用，避免重复计算
        Map<Long, Double> orderRecency = new HashMap<>();
        // 记录订单级综合行为权重，后续识别近 7 天新店时直接复用
        Map<Long, Double> orderBehaviorWeight = new HashMap<>();
        // 表示 30 天窗口内的长期店铺偏好强度
        Map<Long, Double> shopStrength = new HashMap<>();
        // 表示近 7 天活跃店铺强度
        Map<Long, Double> recentShopStrength = new HashMap<>();
        // 表示近 7 天首次出现的新店铺强度
        Map<Long, Double> recentNewShopStrength = new HashMap<>();
        // 记录近 7 天之前出现过的店铺，用于识别真正的新店
        Set<Long> historicalShopIds = new HashSet<>();
        // payWeightedSum 和 payWeightTotal 分别是订单层价格中心值的加权分子与分母，用于先粗算用户消费均价
        BigDecimal payWeightedSum = BigDecimal.ZERO;
        double payWeightTotal = 0D;

        // 第一层聚合以订单为单位构建行为强度
        // 同时融合时间衰减和消费金额权重，避免早期大单长期主导画像
        for (Order order : orders) {
            if (order.getId() == null) {
                continue;
            }
            double recencyWeight = recencyWeight(order.getCreateTime(), now);
            orderRecency.put(order.getId(), recencyWeight);
            double amountWeight = amountWeight(order.getPayAmount());
            double behaviorWeight = recencyWeight * amountWeight;
            orderBehaviorWeight.put(order.getId(), behaviorWeight);
            if (order.getShopId() != null) {
                shopStrength.merge(order.getShopId(), behaviorWeight, Double::sum);
                if (order.getCreateTime() != null && !order.getCreateTime().isBefore(recentStart)) {
                    recentShopStrength.merge(order.getShopId(), behaviorWeight, Double::sum);
                } else {
                    historicalShopIds.add(order.getShopId());
                }
            }
            if (order.getPayAmount() != null && order.getPayAmount().compareTo(BigDecimal.ZERO) > 0) {
                payWeightedSum = payWeightedSum.add(order.getPayAmount().multiply(BigDecimal.valueOf(recencyWeight)));
                payWeightTotal += recencyWeight;
            }
        }
        for (Order order : orders) {
            if (order.getShopId() == null || order.getId() == null) {
                continue;
            }
            if (order.getCreateTime() == null || order.getCreateTime().isBefore(recentStart)) {
                continue;
            }
            if (historicalShopIds.contains(order.getShopId())) {
                continue;
            }
            recentNewShopStrength.merge(order.getShopId(), orderBehaviorWeight.getOrDefault(order.getId(), 0D), Double::sum);
        }

        // favorites 优先级最高
        List<Long> favoriteShopIds = topNKeysByScore(shopStrength, PROFILE_MAX_SHOP_COUNT);
        Set<Long> favoriteShopIdSet = new LinkedHashSet<>(favoriteShopIds);
        // recent new 只保留 favorites 之外的店铺，避免同一店铺被重复加权
        List<Long> recentNewShopIds = topNKeysByScore(excludeKeys(recentNewShopStrength, favoriteShopIdSet), PROFILE_MAX_SHOP_COUNT);
        Set<Long> recentNewShopIdSet = new LinkedHashSet<>(recentNewShopIds);
        // recent active 表示近 7 天活跃但并非最近首次出现的新店，排除 favorites 和 recent new 后保留“近期回访/回流”的店铺
        List<Long> recentActiveShopIds = topNKeysByScore(
                excludeKeys(recentShopStrength, unionSet(favoriteShopIdSet, recentNewShopIdSet)),
                PROFILE_MAX_SHOP_COUNT
        );

        profile.setFavoriteShopIds(favoriteShopIds);
        profile.setRecentNewShopIds(recentNewShopIds);
        profile.setRecentActiveShopIds(recentActiveShopIds);
        profile.setShopPreference(normalizePreference(shopStrength, PROFILE_MAX_SHOP_COUNT));
        profile.setShopStrengthRaw(new LinkedHashMap<>(shopStrength));
        profile.setRecentShopStrengthRaw(new LinkedHashMap<>(recentShopStrength));
        profile.setRecentNewShopStrengthRaw(new LinkedHashMap<>(recentNewShopStrength));
        profile.setPayWeightedSumRaw(payWeightedSum);
        profile.setPayWeightTotalRaw(payWeightTotal);
        profile.setPaidOrderCountRaw(orders.size());

        if (payWeightTotal > 0) {
            profile.setAvgPriceLevel(payWeightedSum.divide(BigDecimal.valueOf(payWeightTotal), 0, RoundingMode.HALF_UP).intValue());
        }

        List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
        if (CollectionUtil.isEmpty(orderIds)) {
            return profile;
        }

        List<OrderItem> orderItems = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .select(OrderItem::getOrderId, OrderItem::getProductCategoryId, OrderItem::getProductPrice, OrderItem::getProductQuantity)
                .in(OrderItem::getOrderId, orderIds));
        if (CollectionUtil.isEmpty(orderItems)) {
            return profile;
        }

        Map<Long, Double> categoryStrength = new HashMap<>();
        BigDecimal weightedPriceSum = BigDecimal.ZERO;
        BigDecimal weightedSquarePriceSum = BigDecimal.ZERO;
        double weightedPriceFactor = 0D;
        
        // 第二层聚合以订单商品为单位细化偏好
        // 类目偏好引入数量和金额信号，价格画像使用加权均值与方差建模区间
        for (OrderItem orderItem : orderItems) {
            Order sourceOrder = orderIndex.get(orderItem.getOrderId());
            if (sourceOrder == null) {
                continue;
            }
            double recencyWeight = orderRecency.getOrDefault(sourceOrder.getId(), recencyWeight(sourceOrder.getCreateTime(), now));
            long quantity = Math.max(orderItem.getProductQuantity() == null ? 1L : orderItem.getProductQuantity(), 1L);
            double quantityWeight = Math.sqrt(quantity);
            BigDecimal productPrice = orderItem.getProductPrice();
            double spendWeight = amountWeight(productPrice == null ? sourceOrder.getPayAmount() : productPrice.multiply(BigDecimal.valueOf(quantity)));
            double itemWeight = recencyWeight * quantityWeight * spendWeight;

            if (orderItem.getProductCategoryId() != null) {
                categoryStrength.merge(orderItem.getProductCategoryId(), itemWeight, Double::sum);
            }

            if (productPrice == null || productPrice.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal quantityBd = BigDecimal.valueOf(quantity);
            BigDecimal weight = BigDecimal.valueOf(recencyWeight).multiply(quantityBd);
            weightedPriceSum = weightedPriceSum.add(productPrice.multiply(weight));
            weightedSquarePriceSum = weightedSquarePriceSum.add(productPrice.multiply(productPrice).multiply(weight));
            weightedPriceFactor += recencyWeight * quantity;
        }
        profile.setCategoryPreference(normalizePreference(categoryStrength, PROFILE_MAX_CATEGORY_COUNT));
        profile.setFavoriteCategoryIds(topNKeysByScore(categoryStrength, PROFILE_MAX_CATEGORY_COUNT));
        profile.setCategoryStrengthRaw(new LinkedHashMap<>(categoryStrength));
        profile.setPaidItemCountRaw(orderItems.size());

        if (weightedPriceFactor > 0) {
            profile.setPriceWeightedSumRaw(weightedPriceSum);
            profile.setPriceWeightedSquareSumRaw(weightedSquarePriceSum);
            profile.setPriceWeightedFactorRaw(weightedPriceFactor);
            double avgPrice = weightedPriceSum.divide(BigDecimal.valueOf(weightedPriceFactor), 4, RoundingMode.HALF_UP).doubleValue();
            double meanSquare = weightedSquarePriceSum.divide(BigDecimal.valueOf(weightedPriceFactor), 4, RoundingMode.HALF_UP).doubleValue();
            double variance = Math.max(0D, meanSquare - avgPrice * avgPrice);
            int avgPriceInt = (int) Math.round(avgPrice);
            // 价格容忍度按标准差放大，最终裁剪到业务可控范围
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

        long recentOrderCount = orders.stream()
                .filter(order -> order.getCreateTime() != null && !order.getCreateTime().isBefore(recentStart))
                .count();
        profile.setRecentPaidOrderCountRaw((int) recentOrderCount);
        // 画像置信度用于控制个性化放大系数
        // 样本量越大、近期行为越充分，个性化权重越高
        double recentRatio = recentOrderCount * 1.0 / Math.max(orders.size(), 1);
        double orderCoverage = Math.log1p(orders.size()) / Math.log(1 + PROFILE_MAX_ORDER_COUNT);
        double itemCoverage = Math.log1p(orderItems.size()) / Math.log(1 + PROFILE_MAX_ORDER_COUNT * 4.0);
        double confidence = 0.45 * orderCoverage + 0.35 * itemCoverage + 0.20 * recentRatio;
        profile.setProfileConfidence(round4(Math.max(0.1D, Math.min(confidence, 1D))));
        return profile;
    }

    /**
     * 把画像写入 Redis
     * @param profile 用户画像对象
     */
    private void saveProfileToCache(UserShopProfile profile) {
        if (profile == null || profile.getUserId() == null) {
            return;
        }
        String key = RedisKeyEnum.HOME_USER_PROFILE.getKey(profile.getUserId());
        try {
            long expire = RedisKeyEnum.HOME_USER_PROFILE.getDefaultExpireSeconds();
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(profile), expire, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("用户画像缓存序列化失败, userId={}", profile.getUserId(), e);
        }
    }

    /**
     * 从画像快照表回源用户画像
     * Redis 未命中时优先读快照表，避免查询链路完全丢失个性化能力
     */
    private UserShopProfile getProfileFromSnapshot(Long userId) {
        UserShopProfileSnapshot snapshot = userShopProfileSnapshotMapper.selectOne(
                new LambdaQueryWrapper<UserShopProfileSnapshot>()
                        .eq(UserShopProfileSnapshot::getUserId, userId)
                        .last("limit 1")
        );
        if (snapshot == null) {
            return null;
        }
        UserShopProfile profile = new UserShopProfile();
        profile.setUserId(snapshot.getUserId());
        // 长期偏好店铺列表
        profile.setFavoriteShopIds(readJsonList(snapshot.getFavoriteShopIdsJson()));
        // 近7天活跃但非新店的店铺列表
        profile.setRecentActiveShopIds(readJsonList(snapshot.getRecentActiveShopIdsJson()));
        // 近7天首次出现的新店铺列表
        profile.setRecentNewShopIds(readJsonList(snapshot.getRecentNewShopIdsJson()));
        // 店铺偏好归一化强度映射
        profile.setShopPreference(readJsonLongDoubleMap(snapshot.getShopPreferenceJson()));
        // 类目TopN兜底列表
        profile.setFavoriteCategoryIds(readJsonList(snapshot.getFavoriteCategoryIdsJson()));
        // 类目偏好归一化强度映射
        profile.setCategoryPreference(readJsonLongDoubleMap(snapshot.getCategoryPreferenceJson()));
        // 价格偏好中心值与区间
        profile.setAvgPriceLevel(snapshot.getAvgPriceLevel());
        profile.setPriceLowerBound(snapshot.getPriceLowerBound());
        profile.setPriceUpperBound(snapshot.getPriceUpperBound());
        profile.setPriceTolerance(snapshot.getPriceTolerance());
        // 画像置信度
        profile.setProfileConfidence(snapshot.getProfileConfidence());
        // 原始聚合强度映射，供支付后增量刷新继续累加
        profile.setShopStrengthRaw(readJsonLongDoubleMap(snapshot.getShopStrengthRawJson()));
        profile.setRecentShopStrengthRaw(readJsonLongDoubleMap(snapshot.getRecentShopStrengthRawJson()));
        profile.setRecentNewShopStrengthRaw(readJsonLongDoubleMap(snapshot.getRecentNewShopStrengthRawJson()));
        profile.setCategoryStrengthRaw(readJsonLongDoubleMap(snapshot.getCategoryStrengthRawJson()));
        // 订单层/商品层价格画像原始统计量
        profile.setPayWeightedSumRaw(snapshot.getPayWeightedSumRaw() == null ? BigDecimal.ZERO : snapshot.getPayWeightedSumRaw());
        profile.setPayWeightTotalRaw(snapshot.getPayWeightTotalRaw() == null ? 0D : snapshot.getPayWeightTotalRaw().doubleValue());
        profile.setPriceWeightedSumRaw(snapshot.getPriceWeightedSumRaw() == null ? BigDecimal.ZERO : snapshot.getPriceWeightedSumRaw());
        profile.setPriceWeightedSquareSumRaw(snapshot.getPriceWeightedSquareSumRaw() == null ? BigDecimal.ZERO : snapshot.getPriceWeightedSquareSumRaw());
        profile.setPriceWeightedFactorRaw(snapshot.getPriceWeightedFactorRaw() == null ? 0D : snapshot.getPriceWeightedFactorRaw().doubleValue());
        // 样本规模统计量，用于置信度重算
        profile.setPaidOrderCountRaw(snapshot.getPaidOrderCountRaw() == null ? 0 : snapshot.getPaidOrderCountRaw());
        profile.setPaidItemCountRaw(snapshot.getPaidItemCountRaw() == null ? 0 : snapshot.getPaidItemCountRaw());
        profile.setRecentPaidOrderCountRaw(snapshot.getRecentPaidOrderCountRaw() == null ? 0 : snapshot.getRecentPaidOrderCountRaw());
        return profile;
    }

    /**
     * 把用户画像持久化到快照表
     * 夜间全量任务负责重建，查询链路只读缓存和快照，不做回源计算
     */
    private void saveProfileSnapshot(UserShopProfile profile) {
        if (profile == null || profile.getUserId() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        UserShopProfileSnapshot existing = userShopProfileSnapshotMapper.selectOne(
                new LambdaQueryWrapper<UserShopProfileSnapshot>()
                        .eq(UserShopProfileSnapshot::getUserId, profile.getUserId())
                        .last("limit 1")
        );
        UserShopProfileSnapshot snapshot = existing == null ? new UserShopProfileSnapshot() : existing;
        snapshot.setUserId(profile.getUserId());
        snapshot.setFavoriteShopIdsJson(writeJson(profile.getFavoriteShopIds()));
        snapshot.setRecentActiveShopIdsJson(writeJson(profile.getRecentActiveShopIds()));
        snapshot.setRecentNewShopIdsJson(writeJson(profile.getRecentNewShopIds()));
        snapshot.setShopPreferenceJson(writeJson(profile.getShopPreference()));
        snapshot.setFavoriteCategoryIdsJson(writeJson(profile.getFavoriteCategoryIds()));
        snapshot.setCategoryPreferenceJson(writeJson(profile.getCategoryPreference()));
        snapshot.setAvgPriceLevel(profile.getAvgPriceLevel());
        snapshot.setPriceLowerBound(profile.getPriceLowerBound());
        snapshot.setPriceUpperBound(profile.getPriceUpperBound());
        snapshot.setPriceTolerance(profile.getPriceTolerance());
        snapshot.setProfileConfidence(profile.getProfileConfidence());
        snapshot.setShopStrengthRawJson(writeJson(profile.getShopStrengthRaw()));
        snapshot.setRecentShopStrengthRawJson(writeJson(profile.getRecentShopStrengthRaw()));
        snapshot.setRecentNewShopStrengthRawJson(writeJson(profile.getRecentNewShopStrengthRaw()));
        snapshot.setCategoryStrengthRawJson(writeJson(profile.getCategoryStrengthRaw()));
        snapshot.setPayWeightedSumRaw(profile.getPayWeightedSumRaw());
        snapshot.setPayWeightTotalRaw(profile.getPayWeightTotalRaw() == null ? null : BigDecimal.valueOf(profile.getPayWeightTotalRaw()));
        snapshot.setPriceWeightedSumRaw(profile.getPriceWeightedSumRaw());
        snapshot.setPriceWeightedSquareSumRaw(profile.getPriceWeightedSquareSumRaw());
        snapshot.setPriceWeightedFactorRaw(profile.getPriceWeightedFactorRaw() == null ? null : BigDecimal.valueOf(profile.getPriceWeightedFactorRaw()));
        snapshot.setPaidOrderCountRaw(profile.getPaidOrderCountRaw());
        snapshot.setPaidItemCountRaw(profile.getPaidItemCountRaw());
        snapshot.setRecentPaidOrderCountRaw(profile.getRecentPaidOrderCountRaw());
        snapshot.setUpdateTime(now);
        if (snapshot.getId() == null) {
            snapshot.setCreateTime(now);
            userShopProfileSnapshotMapper.insert(snapshot);
            return;
        }
        userShopProfileSnapshotMapper.updateById(snapshot);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("序列化用户画像快照字段失败, value={}", value, e);
            return null;
        }
    }

    private List<Long> readJsonList(String json) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("反序列化用户画像快照列表失败, json={}", json, e);
            return Collections.emptyList();
        }
    }

    private Map<Long, Double> readJsonLongDoubleMap(String json) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<Long, Double>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("反序列化用户画像快照映射失败, json={}", json, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 把偏好分数归一化到 0~1 区间
     * @param scoreMap 原始偏好分值
     * @param maxCount 最大保留数量
     */
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
            double ratio = entry.getValue() / peak;
            result.put(entry.getKey(), round4(ratio));
        }
        return result;
    }

    /**
     * 获取分值最高的前 N 个 key
     * @param scoreMap 分值映射
     * @param n 返回数量上限
     */
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

    /**
     * 从分值映射中排除指定 key
     * 用于确保不同画像维度之间不重复叠加同一店铺
     */
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

    /**
     * 合并两个集合
     * 返回新集合，避免污染原始画像维度结果
     */
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

    /**
     * 计算行为时间衰减权重
     * @param eventTime 行为发生时间
     * @param now 当前时间
     */
    private double recencyWeight(LocalDateTime eventTime, LocalDateTime now) {
        if (eventTime == null) {
            return 0.2D;
        }
        // 指数衰减使最近行为更敏感，远期行为自然降权但不完全丢失
        long ageDays = Math.max(Duration.between(eventTime, now).toDays(), 0L);
        // 指数衰减公式：w = e^(-t/λ)
        // t 为行为距今天数，λ 为衰减半径（PROFILE_RECENCY_DECAY_DAYS）
        // 当 t=0 时权重=1，t=λ 时权重约为 0.3679，t 越大权重越接近 0
        return Math.exp(-ageDays / PROFILE_RECENCY_DECAY_DAYS);
    }

    /**
     * 计算消费金额权重
     * @param amount 消费金额
     */
    private double amountWeight(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return 1D;
        }
        // 金额使用对数函数做压缩，避免极端高消费导致权重失真
        return 0.8D + Math.log1p(amount.doubleValue()) / PROFILE_AMOUNT_LOG_BASE;
    }

    /**
     * 浮点值保留四位小数
     * @param value 原始值
     */
    private double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 构建 ES 查询体
     * 召回层面做关键词匹配，排序层面混合距离/质量/热度/画像偏好
     * @param req 搜索请求
     * @param profile 用户画像
     * @param current 页码
     * @param size 每页条数
     * @return ES 查询 JSON
     */
    private String buildESQuery(UserProfileSearchShopBO req, UserShopProfile profile, long current, long size) {
        try {
            ShopSearchBusinessBoostProfile businessBoostProfile = loadBusinessBoostProfile();
            return objectMapper.writeValueAsString(
                    UserProfileEsQueryBuilderSupport.buildQuery(
                            req,
                            profile,
                            userProfileScoreConfig,
                            businessBoostProfile,
                            current,
                            size,
                            SCRIPT_SHOP_ID_LIMIT
                    )
            );
        } catch (Exception e) {
            log.error("构建用户画像ES查询失败, req={}, profile={}", req, profile, e);
            ExceptionUtil.throwException(HomeExceptionEnum.HOME_ES_QUERY_FAIL);
            return "{}"; // 兜底
        }
    }

    /**
     * 加载业务曝光权重
     * 运营规则走数据库，支持商家级连锁投放和店铺级单点加权
     */
    private ShopSearchBusinessBoostProfile loadBusinessBoostProfile() {
        ShopSearchBusinessBoostProfile profile = new ShopSearchBusinessBoostProfile();
        LocalDateTime now = LocalDateTime.now();
        List<ShopSearchBoostConfig> configs = shopSearchBoostConfigMapper.selectList(
                new LambdaQueryWrapper<ShopSearchBoostConfig>()
                        .eq(ShopSearchBoostConfig::getStatus, 1)
                        .and(wrapper -> wrapper.isNull(ShopSearchBoostConfig::getStartTime).or().le(ShopSearchBoostConfig::getStartTime, now))
                        .and(wrapper -> wrapper.isNull(ShopSearchBoostConfig::getEndTime).or().ge(ShopSearchBoostConfig::getEndTime, now))
        );
        if (CollectionUtil.isEmpty(configs)) {
            return profile;
        }
        Map<Long, Double> sellerBoostWeights = new LinkedHashMap<>();
        Map<Long, Double> shopBoostWeights = new LinkedHashMap<>();
        for (ShopSearchBoostConfig config : configs) {
            if (config.getBoostWeight() == null || config.getBoostWeight().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            double weight = config.getBoostWeight().doubleValue();
            if (config.getShopId() != null && config.getShopId() > 0) {
                shopBoostWeights.put(config.getShopId(), weight);
                continue;
            }
            if (config.getSellerId() != null && config.getSellerId() > 0) {
                sellerBoostWeights.put(config.getSellerId(), weight);
            }
        }
        profile.setSellerBoostWeights(sellerBoostWeights);
        profile.setShopBoostWeights(shopBoostWeights);
        return profile;
    }

    /**
     * 解析 ES 响应为分页对象
     * 同时保留标签聚合结果
     * @param responseBody ES 原始响应
     * @param pageResult 分页对象
     * @return 填充后的分页结果
     */
    private PageResult<RecommendShopVO> parseESResult(String responseBody, PageResult<RecommendShopVO> pageResult) {
        try {
            return UserProfileEsResponseParser.parse(responseBody, objectMapper, pageResult);
        } catch (Exception e) {
            log.error("解析用户画像ES响应失败, body={}", responseBody, e);
            ExceptionUtil.throwException(HomeExceptionEnum.HOME_ES_RESPONSE_PARSE_FAIL);
            return pageResult; // 兜底
        }
    }
}
