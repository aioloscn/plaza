package com.aiolos.plaza.home.config;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


/**
 * 用户画像与检索打分配置
 * 配置项可由 Apollo 覆盖，未配置时使用默认值
 */
@Slf4j
@Component
public class UserProfileScoreConfig {

    // Apollo 不可用时按分钟级限频打印告警，避免高并发请求刷屏
    private static final long APOLLO_WARN_INTERVAL_MS = 60_000L;

    // Apollo 命名空间，默认 application，可切换到独立画像配置命名空间
    @Value("${home.user-profile.score.apollo-namespace:application}")
    private String apolloNamespace;

    // function_score 最大分值上限，防止多维函数叠加后分数失控
    @Value("${home.user-profile.score.max-boost:200}")
    private double maxBoostDefault;

    // 默认排序下的距离衰减权重
    @Value("${home.user-profile.score.distance.default-weight:14}")
    private double distanceDefaultWeightDefault;

    // 按距离优先排序(orderBy=1)时的距离衰减权重
    @Value("${home.user-profile.score.distance.order-by-distance-weight:28}")
    private double distanceOrderByDistanceWeightDefault;

    // 店铺评分(score字段)权重
    @Value("${home.user-profile.score.quality.shop-score-weight:4.0}")
    private double shopScoreWeightDefault;

    // 商家评分(seller_score字段)权重
    @Value("${home.user-profile.score.quality.seller-score-weight:2.0}")
    private double sellerScoreWeightDefault;

    // 热度脚本分的整体权重
    @Value("${home.user-profile.score.hot.weight:1.6}")
    private double hotScoreWeightDefault;

    // 细粒度店铺偏好加权的基础分
    @Value("${home.user-profile.score.shop.preference-base-weight:1.8}")
    private double shopPreferenceBaseWeightDefault;

    // 细粒度店铺偏好加权中 strength 的主增益系数
    @Value("${home.user-profile.score.shop.preference-range-weight:5.0}")
    private double shopPreferenceRangeWeightDefault;

    // 细粒度店铺偏好加权中 profileConfidence 的放大系数
    @Value("${home.user-profile.score.shop.preference-confidence-weight:5.0}")
    private double shopPreferenceConfidenceWeightDefault;

    // 无细粒度偏好时 favorites 兜底加权的基础分
    @Value("${home.user-profile.score.shop.favorite-base-weight:5.0}")
    private double favoriteShopBaseWeightDefault;

    // favorites 兜底加权中 profileConfidence 的放大系数
    @Value("${home.user-profile.score.shop.favorite-confidence-weight:2.0}")
    private double favoriteShopConfidenceWeightDefault;

    // recentActive 店铺维度的基础权重
    @Value("${home.user-profile.score.shop.recent-base-weight:3.2}")
    private double recentShopBaseWeightDefault;

    // recentActive 店铺维度中 profileConfidence 的放大系数
    @Value("${home.user-profile.score.shop.recent-confidence-weight:2.0}")
    private double recentShopConfidenceWeightDefault;

    // recentNew 店铺维度的基础权重
    @Value("${home.user-profile.score.shop.recent-new-base-weight:4.2}")
    private double recentNewShopBaseWeightDefault;

    // recentNew 店铺维度中 profileConfidence 的放大系数
    @Value("${home.user-profile.score.shop.recent-new-confidence-weight:2.2}")
    private double recentNewShopConfidenceWeightDefault;

    // 细粒度类目偏好加权的基础分
    @Value("${home.user-profile.score.category.preference-base-weight:1.5}")
    private double categoryPreferenceBaseWeightDefault;

    // 细粒度类目偏好加权中 strength 的主增益系数
    @Value("${home.user-profile.score.category.preference-range-weight:3.5}")
    private double categoryPreferenceRangeWeightDefault;

    // 细粒度类目偏好加权中 profileConfidence 的放大系数
    @Value("${home.user-profile.score.category.preference-confidence-weight:2.5}")
    private double categoryPreferenceConfidenceWeightDefault;

    // 类目 TopN 兜底加权的基础分
    @Value("${home.user-profile.score.category.favorite-base-weight:2.8}")
    private double favoriteCategoryBaseWeightDefault;

    // 类目 TopN 兜底加权中 profileConfidence 的放大系数
    @Value("${home.user-profile.score.category.favorite-confidence-weight:1.6}")
    private double favoriteCategoryConfidenceWeightDefault;

    // 价格偏好脚本分的基础权重
    @Value("${home.user-profile.score.price.base-weight:1.8}")
    private double priceBaseWeightDefault;

    // 价格偏好脚本分中 profileConfidence 的放大系数
    @Value("${home.user-profile.score.price.confidence-weight:2.2}")
    private double priceConfidenceWeightDefault;

    // 关键词召回中 name.clean 字段 boost
    @Value("${home.user-profile.score.keyword.name-clean-boost:3.0}")
    private double keywordNameCleanBoostDefault;

    // 关键词召回中 name 字段 boost
    @Value("${home.user-profile.score.keyword.name-boost:1.5}")
    private double keywordNameBoostDefault;

    // 关键词召回中 tags 字段 boost
    @Value("${home.user-profile.score.keyword.tags-boost:1.0}")
    private double keywordTagsBoostDefault;

    // 关键词召回中 description 字段 boost
    @Value("${home.user-profile.score.keyword.description-boost:1.5}")
    private double keywordDescriptionBoostDefault;

    // 关键词召回中 address 字段 boost
    @Value("${home.user-profile.score.keyword.address-boost:0.6}")
    private double keywordAddressBoostDefault;

    // 距离衰减函数的 scale 参数
    @Value("${home.user-profile.score.distance.scale:3km}")
    private String distanceScaleDefault;

    // 距离衰减函数的 offset 参数
    @Value("${home.user-profile.score.distance.offset:300m}")
    private String distanceOffsetDefault;

    // 距离衰减函数的 decay 参数
    @Value("${home.user-profile.score.distance.decay:0.5}")
    private double distanceDecayDefault;

    // 热度脚本中店铺评分的内部系数
    @Value("${home.user-profile.score.hot.shop-factor:0.7}")
    private double hotShopFactorDefault;

    // 热度脚本中商家评分的内部系数
    @Value("${home.user-profile.score.hot.seller-factor:0.3}")
    private double hotSellerFactorDefault;

    // 热度脚本中店铺评分缺失时的默认值
    @Value("${home.user-profile.score.hot.shop-missing-value:3.0}")
    private double hotShopMissingValueDefault;

    // 热度脚本中商家评分缺失时的默认值
    @Value("${home.user-profile.score.hot.seller-missing-value:3.0}")
    private double hotSellerMissingValueDefault;

    // 画像置信度参与排序前的最低裁剪值
    @Value("${home.user-profile.score.profile-confidence-floor:0.25}")
    private double profileConfidenceFloorDefault;

    // 店铺/类目偏好强度参与计算前的最低裁剪值
    @Value("${home.user-profile.score.preference-strength-floor:0.05}")
    private double preferenceStrengthFloorDefault;

    // 价格脚本中价格缺失时的缺省分
    @Value("${home.user-profile.score.price.missing-score:0.2}")
    private double priceMissingScoreDefault;

    // 价格脚本中中心贴合度的内部系数
    @Value("${home.user-profile.score.price.center-score-weight:0.7}")
    private double priceCenterScoreWeightDefault;

    // 价格脚本中区间可接受度的内部系数
    @Value("${home.user-profile.score.price.range-score-weight:0.3}")
    private double priceRangeScoreWeightDefault;

    // 缺少价格区间时使用的默认容忍值
    @Value("${home.user-profile.score.price.fallback-tolerance:20}")
    private int priceFallbackToleranceDefault;

    // 记录上次 Apollo 读取失败告警时间，用于限频
    private volatile long lastApolloWarnTime;

    public double getMaxBoost() {
        return getDouble("home.user-profile.score.max-boost", maxBoostDefault);
    }

    public double getDistanceDefaultWeight() {
        return getDouble("home.user-profile.score.distance.default-weight", distanceDefaultWeightDefault);
    }

    public double getDistanceOrderByDistanceWeight() {
        return getDouble("home.user-profile.score.distance.order-by-distance-weight", distanceOrderByDistanceWeightDefault);
    }

    public double getShopScoreWeight() {
        return getDouble("home.user-profile.score.quality.shop-score-weight", shopScoreWeightDefault);
    }

    public double getSellerScoreWeight() {
        return getDouble("home.user-profile.score.quality.seller-score-weight", sellerScoreWeightDefault);
    }

    public double getHotScoreWeight() {
        return getDouble("home.user-profile.score.hot.weight", hotScoreWeightDefault);
    }

    public double getShopPreferenceBaseWeight() {
        return getDouble("home.user-profile.score.shop.preference-base-weight", shopPreferenceBaseWeightDefault);
    }

    public double getShopPreferenceRangeWeight() {
        return getDouble("home.user-profile.score.shop.preference-range-weight", shopPreferenceRangeWeightDefault);
    }

    public double getShopPreferenceConfidenceWeight() {
        return getDouble("home.user-profile.score.shop.preference-confidence-weight", shopPreferenceConfidenceWeightDefault);
    }

    public double getFavoriteShopBaseWeight() {
        return getDouble("home.user-profile.score.shop.favorite-base-weight", favoriteShopBaseWeightDefault);
    }

    public double getFavoriteShopConfidenceWeight() {
        return getDouble("home.user-profile.score.shop.favorite-confidence-weight", favoriteShopConfidenceWeightDefault);
    }

    public double getRecentShopBaseWeight() {
        return getDouble("home.user-profile.score.shop.recent-base-weight", recentShopBaseWeightDefault);
    }

    public double getRecentShopConfidenceWeight() {
        return getDouble("home.user-profile.score.shop.recent-confidence-weight", recentShopConfidenceWeightDefault);
    }

    public double getRecentNewShopBaseWeight() {
        return getDouble("home.user-profile.score.shop.recent-new-base-weight", recentNewShopBaseWeightDefault);
    }

    public double getRecentNewShopConfidenceWeight() {
        return getDouble("home.user-profile.score.shop.recent-new-confidence-weight", recentNewShopConfidenceWeightDefault);
    }

    public double getCategoryPreferenceBaseWeight() {
        return getDouble("home.user-profile.score.category.preference-base-weight", categoryPreferenceBaseWeightDefault);
    }

    public double getCategoryPreferenceRangeWeight() {
        return getDouble("home.user-profile.score.category.preference-range-weight", categoryPreferenceRangeWeightDefault);
    }

    public double getCategoryPreferenceConfidenceWeight() {
        return getDouble("home.user-profile.score.category.preference-confidence-weight", categoryPreferenceConfidenceWeightDefault);
    }

    public double getFavoriteCategoryBaseWeight() {
        return getDouble("home.user-profile.score.category.favorite-base-weight", favoriteCategoryBaseWeightDefault);
    }

    public double getFavoriteCategoryConfidenceWeight() {
        return getDouble("home.user-profile.score.category.favorite-confidence-weight", favoriteCategoryConfidenceWeightDefault);
    }

    public double getPriceBaseWeight() {
        return getDouble("home.user-profile.score.price.base-weight", priceBaseWeightDefault);
    }

    public double getPriceConfidenceWeight() {
        return getDouble("home.user-profile.score.price.confidence-weight", priceConfidenceWeightDefault);
    }

    public double getKeywordNameCleanBoost() {
        return getDouble("home.user-profile.score.keyword.name-clean-boost", keywordNameCleanBoostDefault);
    }

    public double getKeywordNameBoost() {
        return getDouble("home.user-profile.score.keyword.name-boost", keywordNameBoostDefault);
    }

    public double getKeywordTagsBoost() {
        return getDouble("home.user-profile.score.keyword.tags-boost", keywordTagsBoostDefault);
    }

    public double getKeywordDescriptionBoost() {
        return getDouble("home.user-profile.score.keyword.description-boost", keywordDescriptionBoostDefault);
    }

    public double getKeywordAddressBoost() {
        return getDouble("home.user-profile.score.keyword.address-boost", keywordAddressBoostDefault);
    }

    public String getDistanceScale() {
        return getNonBlankString("home.user-profile.score.distance.scale", distanceScaleDefault);
    }

    public String getDistanceOffset() {
        return getNonBlankString("home.user-profile.score.distance.offset", distanceOffsetDefault);
    }

    public double getDistanceDecay() {
        return getDouble("home.user-profile.score.distance.decay", distanceDecayDefault);
    }

    public double getHotShopFactor() {
        return getDouble("home.user-profile.score.hot.shop-factor", hotShopFactorDefault);
    }

    public double getHotSellerFactor() {
        return getDouble("home.user-profile.score.hot.seller-factor", hotSellerFactorDefault);
    }

    public double getHotShopMissingValue() {
        return getDouble("home.user-profile.score.hot.shop-missing-value", hotShopMissingValueDefault);
    }

    public double getHotSellerMissingValue() {
        return getDouble("home.user-profile.score.hot.seller-missing-value", hotSellerMissingValueDefault);
    }

    public double getProfileConfidenceFloor() {
        return getDouble("home.user-profile.score.profile-confidence-floor", profileConfidenceFloorDefault);
    }

    public double getPreferenceStrengthFloor() {
        return getDouble("home.user-profile.score.preference-strength-floor", preferenceStrengthFloorDefault);
    }

    public double getPriceMissingScore() {
        return getDouble("home.user-profile.score.price.missing-score", priceMissingScoreDefault);
    }

    public double getPriceCenterScoreWeight() {
        return getDouble("home.user-profile.score.price.center-score-weight", priceCenterScoreWeightDefault);
    }

    public double getPriceRangeScoreWeight() {
        return getDouble("home.user-profile.score.price.range-score-weight", priceRangeScoreWeightDefault);
    }

    public int getPriceFallbackTolerance() {
        return (int) Math.round(getDouble("home.user-profile.score.price.fallback-tolerance", priceFallbackToleranceDefault));
    }

    private double getDouble(String key, double defaultValue) {
        Config config = resolveApolloConfig();
        if (config == null) {
            return defaultValue;
        }
        return config.getDoubleProperty(key, defaultValue);
    }

    private String getString(String key, String defaultValue) {
        Config config = resolveApolloConfig();
        if (config == null) {
            return defaultValue;
        }
        return config.getProperty(key, defaultValue);
    }

    private String getNonBlankString(String key, String defaultValue) {
        String value = getString(key, defaultValue);
        return StringUtils.isBlank(value) ? defaultValue : value;
    }

    private Config resolveApolloConfig() {
        try {
            // 默认读取 application 命名空间，可通过配置切换到独立 namespace
            if (StringUtils.isBlank(apolloNamespace) || "application".equalsIgnoreCase(apolloNamespace)) {
                return ConfigService.getAppConfig();
            }
            return ConfigService.getConfig(apolloNamespace);
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            if (now - lastApolloWarnTime >= APOLLO_WARN_INTERVAL_MS) {
                lastApolloWarnTime = now;
                log.warn("读取 Apollo 用户画像权重配置失败，回退本地默认值, namespace={}", apolloNamespace, e);
            }
            return null;
        }
    }
}
