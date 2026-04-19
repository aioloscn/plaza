package com.aiolos.plaza.home.model.profile;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 搜索业务曝光权重画像
 * 区分商家级和店铺级，供 ES 查询构建阶段注入 function_score
 */
@Data
public class ShopSearchBusinessBoostProfile {

    private Map<Long, Double> sellerBoostWeights = new LinkedHashMap<>();

    private Map<Long, Double> shopBoostWeights = new LinkedHashMap<>();
}
