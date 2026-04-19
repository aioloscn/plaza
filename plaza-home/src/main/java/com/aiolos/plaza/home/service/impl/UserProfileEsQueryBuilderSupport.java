package com.aiolos.plaza.home.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.aiolos.plaza.home.config.UserProfileScoreConfig;
import com.aiolos.plaza.home.model.profile.ShopSearchBusinessBoostProfile;
import com.aiolos.plaza.home.model.bo.UserProfileSearchShopBO;
import com.aiolos.plaza.home.model.profile.UserShopProfile;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户画像 ES 查询构建支持类
 * 负责把搜索请求和画像转为 ES DSL 结构
 */
public final class UserProfileEsQueryBuilderSupport {

    private UserProfileEsQueryBuilderSupport() {
    }

    /**
     * 构建 ES 查询结构
     *
     * @param req 搜索请求
     * @param profile 用户画像
     * @param current 页码
     * @param size 每页条数
     * @param scriptShopIdLimit 脚本可参与计算的店铺数量上限
     * @return ES 查询结构
     */
    public static Map<String, Object> buildQuery(
            UserProfileSearchShopBO req,
            UserShopProfile profile,
            UserProfileScoreConfig scoreConfig,
            ShopSearchBusinessBoostProfile businessBoostProfile,
            long current,
            long size,
            int scriptShopIdLimit
    ) {
        return new UserProfileESQueryBuilder(scriptShopIdLimit, scoreConfig, businessBoostProfile)
                .keyword(req.getKeyword())
                .sellerEnabledFilter()
                .categoryFilter(req.getCategoryId())
                .tagFilter(req.getTag())
                .distanceFunction(req.getLatitude(), req.getLongitude(), req.getOrderBy())
                .shopQualityFunction()
                .hotScoreFunction()
                .profileFunctions(profile)
                .distanceScriptField(req.getLatitude(), req.getLongitude())
                .build(current, size);
    }

    /**
     * ES 查询构建器
     * function_score 负责主排序，script_score 负责热度和价格偏好补充
     */
    private static class UserProfileESQueryBuilder {

        private final int scriptShopIdLimit;
        private final UserProfileScoreConfig scoreConfig;
        private final ShopSearchBusinessBoostProfile businessBoostProfile;
        private final Map<String, Object> root = new LinkedHashMap<>();
        private final Map<String, Object> functionScore = new LinkedHashMap<>();
        private final Map<String, Object> bool = new LinkedHashMap<>();
        private final List<Map<String, Object>> must = new ArrayList<>();
        private final List<Map<String, Object>> filters = new ArrayList<>();
        private final List<Map<String, Object>> functions = new ArrayList<>();
        private final Map<String, Object> scriptFields = new LinkedHashMap<>();

        /**
         * 初始化查询构建器
         * 预置 function_score 基础结构
         *
         * @param scriptShopIdLimit 脚本可参与计算的店铺数量上限
         */
        UserProfileESQueryBuilder(int scriptShopIdLimit, UserProfileScoreConfig scoreConfig, ShopSearchBusinessBoostProfile businessBoostProfile) {
            this.scriptShopIdLimit = scriptShopIdLimit;
            this.scoreConfig = scoreConfig;
            this.businessBoostProfile = businessBoostProfile;
            bool.put("must", must);
            bool.put("filter", filters);

            Map<String, Object> innerQuery = new LinkedHashMap<>();
            innerQuery.put("bool", bool);
            functionScore.put("query", innerQuery);
            functionScore.put("functions", functions);
            functionScore.put("score_mode", "sum");
            functionScore.put("boost_mode", "sum");
            // max_boost 使用配置中心值，未配置时回退默认值，防止函数叠加后分值失控
            functionScore.put("max_boost", scoreConfig == null ? 200D : scoreConfig.getMaxBoost());
        }

        /**
         * 追加关键词召回条件
         *
         * @param keyword 搜索关键词
         * @return 当前构建器
         */
        UserProfileESQueryBuilder keyword(String keyword) {
            if (StringUtils.isBlank(keyword)) {
                return this;
            }
            Map<String, Object> shouldClause = new LinkedHashMap<>();
            List<Map<String, Object>> should = new ArrayList<>();
            should.add(match("name.clean", keyword, getConfigValue(scoreConfig == null ? null : scoreConfig.getKeywordNameCleanBoost(), 3.0)));
            should.add(match("name", keyword, getConfigValue(scoreConfig == null ? null : scoreConfig.getKeywordNameBoost(), 1.5)));
            should.add(match("tags", keyword, getConfigValue(scoreConfig == null ? null : scoreConfig.getKeywordTagsBoost(), 1.0)));
            should.add(match("description", keyword, getConfigValue(scoreConfig == null ? null : scoreConfig.getKeywordDescriptionBoost(), 1.5)));
            should.add(match("address", keyword, getConfigValue(scoreConfig == null ? null : scoreConfig.getKeywordAddressBoost(), 0.6)));
            shouldClause.put("should", should);
            shouldClause.put("minimum_should_match", 1);
            Map<String, Object> boolClause = new LinkedHashMap<>();
            boolClause.put("bool", shouldClause);
            must.add(boolClause);
            return this;
        }

        /**
         * 追加商家可用过滤条件
         *
         * @return 当前构建器
         */
        UserProfileESQueryBuilder sellerEnabledFilter() {
            filters.add(term("seller_disabled_flag", 1));
            return this;
        }

        /**
         * 追加类目过滤条件
         *
         * @param categoryId 类目 ID
         * @return 当前构建器
         */
        UserProfileESQueryBuilder categoryFilter(Long categoryId) {
            if (categoryId != null) {
                filters.add(term("category_id", categoryId));
            }
            return this;
        }

        /**
         * 追加标签过滤条件
         *
         * @param tag 标签值
         * @return 当前构建器
         */
        UserProfileESQueryBuilder tagFilter(String tag) {
            if (StringUtils.isNotBlank(tag)) {
                must.add(term("tags", tag));
            }
            return this;
        }

        /**
         * 追加距离衰减打分函数
         *
         * @param latitude 用户纬度
         * @param longitude 用户经度
         * @param orderBy 排序策略
         * @return 当前构建器
         */
        UserProfileESQueryBuilder distanceFunction(BigDecimal latitude, BigDecimal longitude, Integer orderBy) {
            if (latitude == null || longitude == null) {
                return this;
            }
            Map<String, Object> location = new LinkedHashMap<>();
            location.put("origin", latitude + "," + longitude);
            location.put("scale", scoreConfig == null ? "3km" : scoreConfig.getDistanceScale());
            location.put("offset", scoreConfig == null ? "300m" : scoreConfig.getDistanceOffset());
            location.put("decay", getConfigValue(scoreConfig == null ? null : scoreConfig.getDistanceDecay(), 0.5));

            Map<String, Object> gauss = new LinkedHashMap<>();
            gauss.put("location", location);

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("gauss", gauss);
            function.put(
                    "weight",
                    (orderBy != null && orderBy == 1)
                            ? getConfigValue(scoreConfig == null ? null : scoreConfig.getDistanceOrderByDistanceWeight(), 28D)
                            : getConfigValue(scoreConfig == null ? null : scoreConfig.getDistanceDefaultWeight(), 14D)
            );
            functions.add(function);
            return this;
        }

        /**
         * 追加店铺质量打分函数
         *
         * @return 当前构建器
         */
        UserProfileESQueryBuilder shopQualityFunction() {
            Map<String, Object> shopQuality = new LinkedHashMap<>();
            Map<String, Object> scoreFactor = new LinkedHashMap<>();
            scoreFactor.put("field", "score");
            scoreFactor.put("modifier", "sqrt");
            scoreFactor.put("missing", 1.0);
            shopQuality.put("field_value_factor", scoreFactor);
            shopQuality.put("weight", getConfigValue(scoreConfig == null ? null : scoreConfig.getShopScoreWeight(), 4.0));
            functions.add(shopQuality);

            Map<String, Object> sellerQuality = new LinkedHashMap<>();
            Map<String, Object> sellerFactor = new LinkedHashMap<>();
            sellerFactor.put("field", "seller_score");
            sellerFactor.put("modifier", "sqrt");
            sellerFactor.put("missing", 1.0);
            sellerQuality.put("field_value_factor", sellerFactor);
            sellerQuality.put("weight", getConfigValue(scoreConfig == null ? null : scoreConfig.getSellerScoreWeight(), 2.0));
            functions.add(sellerQuality);
            return this;
        }

        /**
         * 追加热度打分函数
         *
         * @return 当前构建器
         */
        UserProfileESQueryBuilder hotScoreFunction() {
            Map<String, Object> hotFunction = new LinkedHashMap<>();
            Map<String, Object> scriptScore = new LinkedHashMap<>();
            Map<String, Object> script = new LinkedHashMap<>();
            script.put("lang", "painless");
            script.put("source",
                    "double shop = (doc.containsKey('score') && !doc['score'].empty) ? doc['score'].value : params.shopMissing; " +
                            "double seller = (doc.containsKey('seller_score') && !doc['seller_score'].empty) ? doc['seller_score'].value : params.sellerMissing; " +
                            "double hot = shop * params.shopFactor + seller * params.sellerFactor; " +
                            "return Math.log(1 + hot);");
            script.put("params", Map.of(
                    "shopFactor", getConfigValue(scoreConfig == null ? null : scoreConfig.getHotShopFactor(), 0.7),
                    "sellerFactor", getConfigValue(scoreConfig == null ? null : scoreConfig.getHotSellerFactor(), 0.3),
                    "shopMissing", getConfigValue(scoreConfig == null ? null : scoreConfig.getHotShopMissingValue(), 3.0),
                    "sellerMissing", getConfigValue(scoreConfig == null ? null : scoreConfig.getHotSellerMissingValue(), 3.0)
            ));
            scriptScore.put("script", script);
            hotFunction.put("script_score", scriptScore);
            hotFunction.put("weight", getConfigValue(scoreConfig == null ? null : scoreConfig.getHotScoreWeight(), 1.6));
            functions.add(hotFunction);
            return this;
        }

        /**
         * 追加画像个性化打分函数
         *
         * @param profile 用户画像
         * @return 当前构建器
         */
        UserProfileESQueryBuilder profileFunctions(UserShopProfile profile) {
            if (profile == null) {
                return this;
            }
            // 置信度作为画像总闸门，低置信度用户减少个性化干预
            double profileConfidence = clamp(
                    profile.getProfileConfidence(),
                    getConfigValue(scoreConfig == null ? null : scoreConfig.getProfileConfidenceFloor(), 0.25),
                    1.0
            );

            // 画像入库时已做一次去重，这里再做一层保护，避免历史缓存脏数据导致重复加权
            List<Long> favoriteShops = cap(profile.getFavoriteShopIds(), scriptShopIdLimit);
            Set<Long> favoriteShopSet = new LinkedHashSet<>(favoriteShops);
            List<Long> recentNewShops = cap(excludeIds(profile.getRecentNewShopIds(), favoriteShopSet), scriptShopIdLimit);
            Set<Long> recentNewShopSet = new LinkedHashSet<>(recentNewShops);
            List<Long> recentShops = cap(excludeIds(profile.getRecentActiveShopIds(), unionSet(favoriteShopSet, recentNewShopSet)), scriptShopIdLimit);
            List<Map.Entry<Long, Double>> shopPreference = capEntriesByScore(profile.getShopPreference(), scriptShopIdLimit);
            // 记录本次已加权店铺，确保同一请求内同一 shopId 只被加权一次
            Set<Long> weightedShopSet = new LinkedHashSet<>();

            if (CollectionUtil.isNotEmpty(shopPreference)) {
                // 按店铺偏好强度逐条注入权重，实现细粒度个性化
                for (Map.Entry<Long, Double> entry : shopPreference) {
                    double strength = clamp(
                            entry.getValue(),
                            getConfigValue(scoreConfig == null ? null : scoreConfig.getPreferenceStrengthFloor(), 0.05),
                            1.0
                    );
                    double weight = getConfigValue(scoreConfig == null ? null : scoreConfig.getShopPreferenceBaseWeight(), 1.8)
                            + strength * (
                            getConfigValue(scoreConfig == null ? null : scoreConfig.getShopPreferenceRangeWeight(), 5.0)
                                    + profileConfidence * getConfigValue(scoreConfig == null ? null : scoreConfig.getShopPreferenceConfidenceWeight(), 5.0)
                    );
                    functions.add(weightWithFilter(term("id", entry.getKey()), weight));
                    weightedShopSet.add(entry.getKey());
                }
            } else if (CollectionUtil.isNotEmpty(favoriteShops)) {
                // 无细粒度偏好时，使用 favorites 做粗粒度兜底
                List<Long> dedupedFavoriteShops = excludeIds(favoriteShops, weightedShopSet);
                if (CollectionUtil.isNotEmpty(dedupedFavoriteShops)) {
                    functions.add(weightWithFilter(
                            terms("id", dedupedFavoriteShops),
                            getConfigValue(scoreConfig == null ? null : scoreConfig.getFavoriteShopBaseWeight(), 5.0)
                                    + getConfigValue(scoreConfig == null ? null : scoreConfig.getFavoriteShopConfidenceWeight(), 2.0) * profileConfidence
                    ));
                    weightedShopSet.addAll(dedupedFavoriteShops);
                }
            }
            if (CollectionUtil.isNotEmpty(recentNewShops)) {
                // recentNew 代表用户最近新探索的店铺，和 favorites、recentActive 语义不同
                List<Long> dedupedRecentNewShops = excludeIds(recentNewShops, weightedShopSet);
                if (CollectionUtil.isNotEmpty(dedupedRecentNewShops)) {
                    functions.add(weightWithFilter(
                            terms("id", dedupedRecentNewShops),
                            getConfigValue(scoreConfig == null ? null : scoreConfig.getRecentNewShopBaseWeight(), 4.2)
                                    + getConfigValue(scoreConfig == null ? null : scoreConfig.getRecentNewShopConfidenceWeight(), 2.2) * profileConfidence
                    ));
                    weightedShopSet.addAll(dedupedRecentNewShops);
                }
            }
            if (CollectionUtil.isNotEmpty(recentShops)) {
                // recentActive 代表近期回访或回流店铺，不包含 recentNew
                List<Long> dedupedRecentShops = excludeIds(recentShops, weightedShopSet);
                if (CollectionUtil.isNotEmpty(dedupedRecentShops)) {
                    functions.add(weightWithFilter(
                            terms("id", dedupedRecentShops),
                            getConfigValue(scoreConfig == null ? null : scoreConfig.getRecentShopBaseWeight(), 3.2)
                                    + getConfigValue(scoreConfig == null ? null : scoreConfig.getRecentShopConfidenceWeight(), 2.0) * profileConfidence
                    ));
                    weightedShopSet.addAll(dedupedRecentShops);
                }
            }

            if (profile.getCategoryPreference() != null && !profile.getCategoryPreference().isEmpty()) {
                profile.getCategoryPreference().forEach((categoryId, strength) -> {
                    if (categoryId == null || strength == null || strength <= 0) {
                        return;
                    }
                    double weight = getConfigValue(scoreConfig == null ? null : scoreConfig.getCategoryPreferenceBaseWeight(), 1.5)
                            + clamp(
                            strength,
                            getConfigValue(scoreConfig == null ? null : scoreConfig.getPreferenceStrengthFloor(), 0.05),
                            1.0
                    ) * (
                            getConfigValue(scoreConfig == null ? null : scoreConfig.getCategoryPreferenceRangeWeight(), 3.5)
                                    + getConfigValue(scoreConfig == null ? null : scoreConfig.getCategoryPreferenceConfidenceWeight(), 2.5) * profileConfidence
                    );
                    functions.add(weightWithFilter(term("category_id", categoryId), weight));
                });
            } else {
                // 类目强度缺失时，使用 TopN 类目 ID 列表做兜底加权
                List<Long> favoriteCategories = cap(profile.getFavoriteCategoryIds(), scriptShopIdLimit);
                if (CollectionUtil.isNotEmpty(favoriteCategories)) {
                    functions.add(weightWithFilter(
                            terms("category_id", favoriteCategories),
                            getConfigValue(scoreConfig == null ? null : scoreConfig.getFavoriteCategoryBaseWeight(), 2.8)
                                    + getConfigValue(scoreConfig == null ? null : scoreConfig.getFavoriteCategoryConfidenceWeight(), 1.6) * profileConfidence
                    ));
                }
            }

            if (profile.getAvgPriceLevel() != null && profile.getAvgPriceLevel() > 0) {
                Map<String, Object> priceAffinity = new LinkedHashMap<>();
                Map<String, Object> scriptScore = new LinkedHashMap<>();
                Map<String, Object> script = new LinkedHashMap<>();
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("target", profile.getAvgPriceLevel());
                int fallbackTolerance = scoreConfig == null ? 20 : scoreConfig.getPriceFallbackTolerance();
                params.put("lower", profile.getPriceLowerBound() == null ? Math.max(1, profile.getAvgPriceLevel() - fallbackTolerance) : profile.getPriceLowerBound());
                params.put("upper", profile.getPriceUpperBound() == null ? profile.getAvgPriceLevel() + fallbackTolerance : profile.getPriceUpperBound());
                params.put("tolerance", profile.getPriceTolerance() == null ? fallbackTolerance : profile.getPriceTolerance());
                params.put("missingScore", getConfigValue(scoreConfig == null ? null : scoreConfig.getPriceMissingScore(), 0.2));
                params.put("centerWeight", getConfigValue(scoreConfig == null ? null : scoreConfig.getPriceCenterScoreWeight(), 0.7));
                params.put("rangeWeight", getConfigValue(scoreConfig == null ? null : scoreConfig.getPriceRangeScoreWeight(), 0.3));
                script.put("lang", "painless");
                // 价格打分由中心贴合度和区间可接受度组成
                // 中心用于排序细分，区间用于抑制过高或过低价格结果
                script.put("source",
                        "if (!doc.containsKey('per_capita_price') || doc['per_capita_price'].empty) return params.missingScore; " +
                                "double price = doc['per_capita_price'].value; " +
                                "double target = params.target; " +
                                "double lower = params.lower; " +
                                "double upper = params.upper; " +
                                "double tolerance = Math.max(params.tolerance, 1.0); " +
                                "double centerScore = Math.exp(-Math.abs(price - target) / tolerance); " +
                                "double edgeGap = 0.0; " +
                                "if (price < lower) { edgeGap = lower - price; } else if (price > upper) { edgeGap = price - upper; } " +
                                "double rangeScore = edgeGap <= 0 ? 1.0 : Math.exp(-edgeGap / tolerance); " +
                                "return centerScore * params.centerWeight + rangeScore * params.rangeWeight;");
                script.put("params", params);
                scriptScore.put("script", script);
                priceAffinity.put("script_score", scriptScore);
                priceAffinity.put(
                        "weight",
                        getConfigValue(scoreConfig == null ? null : scoreConfig.getPriceBaseWeight(), 1.8)
                                + getConfigValue(scoreConfig == null ? null : scoreConfig.getPriceConfidenceWeight(), 2.2) * profileConfidence
                );
                functions.add(priceAffinity);
            }

            applyBusinessBoostFunctions();
            return this;
        }

        /**
         * 追加业务曝光权重
         * 规则参数走 Apollo，具体投放对象和权重走 DB，便于运营系统实时调节
         */
        private void applyBusinessBoostFunctions() {
            if (businessBoostProfile == null) {
                return;
            }
            if (businessBoostProfile.getSellerBoostWeights() != null) {
                businessBoostProfile.getSellerBoostWeights().forEach((sellerId, weight) -> {
                    if (sellerId == null || sellerId <= 0 || weight == null || weight <= 0) {
                        return;
                    }
                    functions.add(weightWithFilter(term("seller_id", sellerId), weight));
                });
            }
            if (businessBoostProfile.getShopBoostWeights() != null) {
                businessBoostProfile.getShopBoostWeights().forEach((shopId, weight) -> {
                    if (shopId == null || shopId <= 0 || weight == null || weight <= 0) {
                        return;
                    }
                    functions.add(weightWithFilter(term("id", shopId), weight));
                });
            }
        }

        /**
         * 追加距离脚本字段
         *
         * @param latitude 用户纬度
         * @param longitude 用户经度
         * @return 当前构建器
         */
        UserProfileESQueryBuilder distanceScriptField(BigDecimal latitude, BigDecimal longitude) {
            if (latitude == null || longitude == null) {
                return this;
            }
            Map<String, Object> distanceField = new LinkedHashMap<>();
            Map<String, Object> script = new LinkedHashMap<>();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("lat", latitude);
            params.put("lon", longitude);
            script.put("source", "haversin(lat,lon,doc['location'].lat,doc['location'].lon)");
            script.put("lang", "expression");
            script.put("params", params);
            distanceField.put("script", script);
            scriptFields.put("distance", distanceField);
            return this;
        }

        /**
         * 生成最终 ES 查询结构
         *
         * @param current 页码
         * @param size 每页条数
         * @return ES 查询结构
         */
        Map<String, Object> build(long current, long size) {
            root.put("_source", List.of("*"));
            root.put("from", Math.max((current - 1) * size, 0));
            root.put("size", size);

            Map<String, Object> query = new LinkedHashMap<>();
            query.put("function_score", functionScore);
            root.put("query", query);

            if (!scriptFields.isEmpty()) {
                root.put("script_fields", scriptFields);
            }

            Map<String, Object> aggs = new LinkedHashMap<>();
            Map<String, Object> groupByTags = new LinkedHashMap<>();
            groupByTags.put("terms", Map.of("field", "tags"));
            aggs.put("group_by_tags", groupByTags);
            root.put("aggs", aggs);
            return root;
        }

        /**
         * 构建 match 子句
         *
         * @param field 目标字段
         * @param keyword 关键词
         * @param boost 权重
         * @return match 语句
         */
        private static Map<String, Object> match(String field, String keyword, double boost) {
            Map<String, Object> match = new LinkedHashMap<>();
            Map<String, Object> fieldQuery = new LinkedHashMap<>();
            fieldQuery.put("query", keyword);
            fieldQuery.put("boost", boost);
            match.put("match", Map.of(field, fieldQuery));
            return match;
        }

        /**
         * 构建 term 子句
         *
         * @param field 目标字段
         * @param value 匹配值
         * @return term 语句
         */
        private static Map<String, Object> term(String field, Object value) {
            return Map.of("term", Map.of(field, value));
        }

        /**
         * 构建 terms 子句
         *
         * @param field 目标字段
         * @param values 匹配值列表
         * @return terms 语句
         */
        private static Map<String, Object> terms(String field, List<Long> values) {
            return Map.of("terms", Map.of(field, values));
        }

        /**
         * 构建带过滤条件的加权函数
         *
         * @param filter 过滤条件
         * @param weight 权重值
         * @return 加权函数结构
         */
        private static Map<String, Object> weightWithFilter(Map<String, Object> filter, double weight) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("filter", filter);
            function.put("weight", weight);
            return function;
        }

        /**
         * 截断并去重 ID 列表
         *
         * @param source 原始列表
         * @param limit 最大保留数量
         * @return 截断后的列表
         */
        private static List<Long> cap(List<Long> source, int limit) {
            if (CollectionUtil.isEmpty(source)) {
                return Collections.emptyList();
            }
            return source.stream().filter(Objects::nonNull).distinct().limit(limit).collect(Collectors.toList());
        }

        /**
         * 从列表中剔除已出现的 ID
         *
         * @param source 原始列表
         * @param excludedIds 已占用 ID
         * @return 去重后的列表
         */
        private static List<Long> excludeIds(List<Long> source, Set<Long> excludedIds) {
            if (CollectionUtil.isEmpty(source)) {
                return Collections.emptyList();
            }
            if (CollectionUtil.isEmpty(excludedIds)) {
                return source;
            }
            return source.stream()
                    .filter(Objects::nonNull)
                    .filter(id -> !excludedIds.contains(id))
                    .distinct()
                    .collect(Collectors.toList());
        }

        /**
         * 按分值排序并截断映射条目
         *
         * @param source 分值映射
         * @param limit 最大保留数量
         * @return 排序截断后的条目列表
         */
        private static List<Map.Entry<Long, Double>> capEntriesByScore(Map<Long, Double> source, int limit) {
            if (source == null || source.isEmpty()) {
                return Collections.emptyList();
            }
            return source.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0)
                    .sorted(Comparator.comparing(Map.Entry<Long, Double>::getValue).reversed())
                    .limit(limit)
                    .toList();
        }

        /**
         * 对数值做上下界裁剪
         *
         * @param value 原始值
         * @param min 下界
         * @param max 上界
         * @return 裁剪后的值
         */
        private static double clamp(Double value, double min, double max) {
            if (value == null) {
                return min;
            }
            return Math.max(min, Math.min(value, max));
        }

        /**
         * 合并两个 ID 集合
         *
         * @param left 左侧集合
         * @param right 右侧集合
         * @return 新集合
         */
        private static Set<Long> unionSet(Set<Long> left, Set<Long> right) {
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
         * 读取配置值
         *
         * @param configured 配置值
         * @param defaultValue 默认值
         * @return 实际生效值
         */
        private static double getConfigValue(Double configured, double defaultValue) {
            if (configured == null || configured <= 0) {
                return defaultValue;
            }
            return configured;
        }
    }
}
