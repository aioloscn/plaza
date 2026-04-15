package com.aiolos.plaza.home.model.profile;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户门店画像
 * 由历史订单行为聚合得到
 */
@Data
public class UserShopProfile {

    private Long userId;

    private List<Long> favoriteShopIds = new ArrayList<>();

    private List<Long> recentActiveShopIds = new ArrayList<>();

    /**
     * key: shopId, value: [0,1] 偏好强度
     */
    private Map<Long, Double> shopPreference = new LinkedHashMap<>();

    /**
     * key: categoryId, value: [0,1] 偏好强度
     */
    private Map<Long, Double> categoryPreference = new LinkedHashMap<>();

    /**
     * 用户可接受价格中心值（元）
     */
    private Integer avgPriceLevel;

    /**
     * 用户价格偏好区间下界（元）
     */
    private Integer priceLowerBound;

    /**
     * 用户价格偏好区间上界（元）
     */
    private Integer priceUpperBound;

    /**
     * 用户价格容忍波动（元）
     */
    private Integer priceTolerance;

    /**
     * 画像可信度 [0,1]
     */
    private Double profileConfidence;
}
