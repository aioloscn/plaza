package com.aiolos.plaza.home.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.aiolos.common.wrapper.PageResult;
import com.aiolos.plaza.home.model.vo.RecommendShopVO;
import com.aiolos.plaza.home.model.vo.ShopTagAggrVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 用户画像 ES 响应解析器
 * 负责把 ES JSON 响应转换为分页结果对象
 */
public final class UserProfileEsResponseParser {

    private UserProfileEsResponseParser() {
    }

    /**
     * 解析 ES 响应
     *
     * @param responseBody ES 原始响应
     * @param objectMapper JSON 工具
     * @param pageResult 目标分页对象
     * @return 填充后的分页结果
     * @throws Exception 解析异常
     */
    public static PageResult<RecommendShopVO> parse(
            String responseBody,
            ObjectMapper objectMapper,
            PageResult<RecommendShopVO> pageResult
    ) throws Exception {
        JsonNode responseJson = objectMapper.readTree(responseBody);
        JsonNode hits = responseJson.path("hits");
        pageResult.setTotal(hits.path("total").path("value").asLong(0L));

        List<RecommendShopVO> records = new ArrayList<>();
        JsonNode hitArray = hits.path("hits");
        if (hitArray.isArray()) {
            for (JsonNode hit : hitArray) {
                JsonNode source = hit.path("_source");
                if (source.isMissingNode() || source.isNull()) {
                    continue;
                }
                JsonNode fields = hit.path("fields");
                RecommendShopVO vo = new RecommendShopVO();
                vo.setId(source.path("id").asLong());
                vo.setName(source.path("name").asText(null));
                vo.setIconUrl(source.path("icon_url").asText(null));
                vo.setAddress(source.path("address").asText(null));
                vo.setDescription(source.path("description").asText(null));
                vo.setCategoryId(source.path("category_id").isMissingNode() ? null : source.path("category_id").asLong());
                vo.setCategoryName(source.path("category_name").asText(null));
                vo.setScore(source.path("score").isMissingNode() ? null : source.path("score").decimalValue());
                vo.setPerCapitaPrice(source.path("per_capita_price").isMissingNode() ? null : source.path("per_capita_price").asInt());
                vo.setTags(source.path("tags").asText(null));
                vo.setSellerId(source.path("seller_id").isMissingNode() ? null : source.path("seller_id").asLong());
                vo.setSellerScore(source.path("seller_score").isMissingNode() ? null : source.path("seller_score").decimalValue());
                vo.setSellerDisabledFlag(source.path("seller_disabled_flag").isMissingNode() ? null : source.path("seller_disabled_flag").asInt());
                vo.setDistance(resolveDistance(fields));
                records.add(vo);
            }
        }

        List<ShopTagAggrVO> tagAggregations = parseTagAgg(responseJson.path("aggregations"));
        if (!records.isEmpty() && CollectionUtil.isNotEmpty(tagAggregations)) {
            records.get(0).setTagAggregations(tagAggregations);
        }
        pageResult.setRecords(records);
        return pageResult;
    }

    /**
     * 解析标签聚合结果
     *
     * @param aggregations ES 聚合节点
     * @return 标签聚合列表
     */
    private static List<ShopTagAggrVO> parseTagAgg(JsonNode aggregations) {
        if (aggregations == null || aggregations.isMissingNode()) {
            return Collections.emptyList();
        }
        JsonNode buckets = aggregations.path("group_by_tags").path("buckets");
        if (!buckets.isArray()) {
            return Collections.emptyList();
        }
        List<ShopTagAggrVO> result = new ArrayList<>();
        for (JsonNode bucket : buckets) {
            result.add(new ShopTagAggrVO(bucket.path("key").asText(), bucket.path("doc_count").asInt()));
        }
        return result;
    }

    /**
     * 把距离字段格式化为 km 或 m
     *
     * @param fields ES fields 节点
     * @return 格式化后的距离字符串
     */
    private static String resolveDistance(JsonNode fields) {
        if (fields == null || fields.isMissingNode() || !fields.has("distance")) {
            return null;
        }
        JsonNode distanceNode = fields.path("distance");
        if (!distanceNode.isArray() || distanceNode.isEmpty()) {
            return null;
        }
        double distanceKm = distanceNode.get(0).asDouble(0D);
        if (distanceKm < 1) {
            return String.format("%.0fm", distanceKm * 1000);
        }
        return String.format("%.1fkm", distanceKm);
    }
}
