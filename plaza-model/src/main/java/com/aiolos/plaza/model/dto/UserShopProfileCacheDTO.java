package com.aiolos.plaza.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户画像缓存 DTO
 * 供订单侧直接写入 plaza-home Redis 画像缓存使用，字段与 home 侧缓存结构保持一致
 */
@Data
public class UserShopProfileCacheDTO {

    private Long userId;

    private List<Long> favoriteShopIds = new ArrayList<>();

    private List<Long> recentActiveShopIds = new ArrayList<>();

    private List<Long> recentNewShopIds = new ArrayList<>();

    private Map<Long, Double> shopPreference = new LinkedHashMap<>();

    private List<Long> favoriteCategoryIds = new ArrayList<>();

    private Map<Long, Double> categoryPreference = new LinkedHashMap<>();

    private Integer avgPriceLevel;

    private Integer priceLowerBound;

    private Integer priceUpperBound;

    private Integer priceTolerance;

    private Double profileConfidence;

    private Map<Long, Double> shopStrengthRaw = new LinkedHashMap<>();

    private Map<Long, Double> recentShopStrengthRaw = new LinkedHashMap<>();

    private Map<Long, Double> recentNewShopStrengthRaw = new LinkedHashMap<>();

    private Map<Long, Double> categoryStrengthRaw = new LinkedHashMap<>();

    private BigDecimal payWeightedSumRaw = BigDecimal.ZERO;

    private Double payWeightTotalRaw = 0D;

    private BigDecimal priceWeightedSumRaw = BigDecimal.ZERO;

    private BigDecimal priceWeightedSquareSumRaw = BigDecimal.ZERO;

    private Double priceWeightedFactorRaw = 0D;

    private Integer paidOrderCountRaw = 0;

    private Integer paidItemCountRaw = 0;

    private Integer recentPaidOrderCountRaw = 0;
}
