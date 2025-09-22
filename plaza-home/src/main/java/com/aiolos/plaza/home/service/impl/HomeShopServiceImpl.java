package com.aiolos.plaza.home.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.map.MapUtil;
import com.aiolos.common.enums.base.BoolEnum;
import com.aiolos.common.util.ConvertBeanUtil;
import com.aiolos.common.wrapper.PageModel;
import com.aiolos.common.wrapper.PageResult;
import com.aiolos.plaza.home.model.bo.RecommendShopBO;
import com.aiolos.plaza.home.model.bo.SearchShopBO;
import com.aiolos.plaza.home.model.vo.RecommendShopVO;
import com.aiolos.plaza.home.model.vo.ShopTagAggrVO;
import com.aiolos.plaza.home.service.HomeShopService;
import com.aiolos.plaza.mapper.ShopMapper;
import com.aiolos.plaza.model.po.Shop;
import com.aiolos.plaza.service.ShopService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class HomeShopServiceImpl implements HomeShopService {
    
    private final ShopService shopService;
    private final ShopMapper shopMapper;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public PageResult<RecommendShopVO> recommend(PageModel<RecommendShopBO> model) {

        RecommendShopBO data = model.getData();
        QueryWrapper<Shop> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", BoolEnum.YES.getCode());
        long total = shopService.count(queryWrapper);
        List<Shop> list = shopMapper.recommend(data.getLatitude().doubleValue(), data.getLongitude().doubleValue());
        PageResult<RecommendShopVO> pageResult = new PageResult<>();
        pageResult.setCurrent(model.getCurrent());
        pageResult.setSize(model.getSize());
        pageResult.setTotal(total);
        
        if (CollectionUtil.isNotEmpty(list)) {
            List<RecommendShopVO> records = list.stream().map(shop -> {
                RecommendShopVO vo = ConvertBeanUtil.convert(shop, RecommendShopVO.class);
                if (shop.getDistance() != null && shop.getDistance() > 1000) {
                    vo.setDistance(shop.getDistance() / 1000 + "km");
                } else {
                    vo.setDistance(shop.getDistance() != null ? shop.getDistance() + "m" : "");
                }
                return vo;
            }).collect(Collectors.toList());
            pageResult.setRecords(records);
        }
        return pageResult;
    }

    @Override
    public PageResult<RecommendShopVO> search(PageModel<SearchShopBO> model) {
        
        SearchShopBO data = model.getData();
        QueryWrapper<Shop> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", BoolEnum.YES.getCode());
        if (data.getCategoryId() != null) {
            queryWrapper.eq("category_id", data.getCategoryId());
        }
        queryWrapper.and(wrapper -> wrapper
                .like("name", data.getKeyword())
                .or().like("tags", data.getKeyword())
                .or().like("address", data.getKeyword()));
        long total = shopService.count(queryWrapper);
        
        List<Shop> list = shopMapper.search(data.getLatitude().doubleValue(), data.getLongitude().doubleValue(), data.getKeyword(), data.getCategoryId(), data.getOrderBy());
        PageResult<RecommendShopVO> pageResult = new PageResult<>();
        pageResult.setCurrent(model.getCurrent());
        pageResult.setSize(model.getSize());
        pageResult.setTotal(total);
        
        if (CollectionUtil.isNotEmpty(list)) {
            List<RecommendShopVO> records = list.stream().map(shop -> {
                RecommendShopVO vo = ConvertBeanUtil.convert(shop, RecommendShopVO.class);
                if (shop.getDistance() != null && shop.getDistance() > 1000) {
                    vo.setDistance(shop.getDistance() / 1000 + "km");
                } else {
                    vo.setDistance(shop.getDistance() != null ? shop.getDistance() + "m" : "");
                }
                return vo;
            }).collect(Collectors.toList());
            pageResult.setRecords(records);
        }
        return pageResult;
    }
    
    @Override
    public PageResult<RecommendShopVO> searchES(PageModel<SearchShopBO> model) {
        SearchShopBO data = model.getData();
        PageResult<RecommendShopVO> pageResult = new PageResult<>();
        pageResult.setCurrent(model.getCurrent());
        pageResult.setSize(model.getSize());
        
        try {
            // 构建Elasticsearch查询请求
            String queryJson = buildESQuery(data, model.getCurrent(), model.getSize());
            log.info(queryJson);
            
            Request request = new Request("GET", "/shop/_search");
            request.setJsonEntity(queryJson);
            
            Response response = restClient.performRequest(request);
            String responseBody = new String(response.getEntity().getContent().readAllBytes());
            
            // 解析响应
            JsonNode responseJson = objectMapper.readTree(responseBody);
            JsonNode hits = responseJson.get("hits");
            
            // 设置总数
            long total = hits.get("total").get("value").asLong();
            pageResult.setTotal(total);
            
            // 解析结果
            List<RecommendShopVO> records = new ArrayList<>();
            JsonNode hitsArray = hits.get("hits");
            
            for (JsonNode hit : hitsArray) {
                JsonNode source = hit.get("_source");
                JsonNode fields = hit.get("fields");
                if (source == null) continue;
                
                RecommendShopVO vo = new RecommendShopVO();
                vo.setId(source.get("id").asLong());
                vo.setName(source.get("name").asText());
                vo.setIconUrl(source.get("icon_url") != null ? source.get("icon_url").asText() : null);
                vo.setAddress(source.get("address") != null ? source.get("address").asText() : null);
                vo.setCategoryId(source.get("category_id") != null ? source.get("category_id").asLong() : null);
                vo.setCategoryName(source.get("category_name") != null ? source.get("category_name").asText() : null);
                vo.setScore(source.get("score") != null ? source.get("score").decimalValue() : null);
                vo.setPerCapitaPrice(source.get("per_capita_price") != null ? source.get("per_capita_price").asInt() : null);
                vo.setTags(source.get("tags") != null ? source.get("tags").asText() : null);
                vo.setSellerId(source.get("seller_id") != null ? source.get("seller_id").asLong() : null);
                vo.setSellerScore(source.get("seller_score") != null ? source.get("seller_score").decimalValue() : null);
                vo.setSellerDisabledFlag(source.get("seller_disabled_flag") != null ? source.get("seller_disabled_flag").asInt() : null);
                
                // 处理距离字段
                if (fields != null && fields.get("distance") != null) {
                    double distance = fields.get("distance").get(0).asDouble();
                    if (distance < 1) {
                        vo.setDistance(String.format("%.0fm", distance * 1000));
                    } else {
                        vo.setDistance(String.format("%.1fkm", distance));
                    }
                }
                
                records.add(vo);
            }

            // 解析聚合数据
            List<ShopTagAggrVO> tagAggregations = new ArrayList<>();
            JsonNode aggregations = responseJson.get("aggregations");
            if (aggregations != null && aggregations.get("group_by_tags") != null) {
                JsonNode groupByTags = aggregations.get("group_by_tags");
                JsonNode buckets = groupByTags.get("buckets");
                
                if (buckets != null && buckets.isArray()) {
                    for (JsonNode bucket : buckets) {
                        String tag = bucket.get("key").asText();
                        Integer count = bucket.get("doc_count").asInt();
                        tagAggregations.add(new ShopTagAggrVO(tag, count));
                    }
                }
            }
            
            // 将聚合数据添加到第一个记录中
            if (!records.isEmpty() && !tagAggregations.isEmpty()) {
                records.get(0).setTagAggregations(tagAggregations);
            }

            pageResult.setRecords(records);
            
        } catch (IOException e) {
            throw new RuntimeException("Elasticsearch查询失败", e);
        }
        
        return pageResult;
    }

    
    /**
     * 构建Elasticsearch查询
     */
    private String buildESQuery(SearchShopBO data, long current, long size) {
        try {
            ESQueryBuilder queryBuilder = new ESQueryBuilder()
                    .keywordMatch(data.getKeyword(), data.getTag())
                    .sellerEnabledFilter()
                    .categoryFilter(data.getCategoryId())
                    .geoGaussDecay(data.getLatitude(), data.getLongitude(), data.getOrderBy())
                    .scoreFieldValueFactor()
                    .haversinDistanceField(data.getLatitude(), data.getLongitude());
            
            return objectMapper.writeValueAsString(queryBuilder.build(current, size));
        } catch (Exception e) {
            throw new RuntimeException("构建ES查询失败", e);
        }
    }
    
    /**
     * Elasticsearch查询构建器
     * 使用function_score查询，支持地理位置衰减和评分因子
     */
    private static class ESQueryBuilder {
        private final Map<String, Object> functionScoreQuery = new HashMap<>();
        private final Map<String, Object> boolQuery = new HashMap<>();
        private final List<Map<String, Object>> mustClauses = new ArrayList<>();
        private final List<Map<String, Object>> functions = new ArrayList<>();
        private final Map<String, Object> scriptFields = new HashMap<>();
        
        public ESQueryBuilder() {
            boolQuery.put("must", mustClauses);
            
            // 构建function_score查询结构
            Map<String, Object> innerQuery = new HashMap<>();
            innerQuery.put("bool", boolQuery);
            
            functionScoreQuery.put("query", innerQuery);
            functionScoreQuery.put("functions", functions);
            functionScoreQuery.put("score_mode", "sum");
            functionScoreQuery.put("boost_mode", "sum");
        }
        
        /**
         * 添加关键词匹配查询（使用name.clean字段）
         */
        public ESQueryBuilder keywordMatch(String keyword, String tag) {
            if (keyword != null && !keyword.trim().isEmpty()) {
                Map<String, Object> boolClause = new HashMap<>();
                Map<String, Object> subBoolQuery = new HashMap<>();
                List<Map<String, Object>> shouldClauses = new ArrayList<>();
                
                // 添加name.clean匹配
                Map<String, Object> matchClause = new HashMap<>();
                Map<String, Object> nameCleanMatch = new HashMap<>();
                Map<String, Object> matchQuery = new HashMap<>();
                matchQuery.put("query", keyword);
                matchQuery.put("boost", 1.2);
                nameCleanMatch.put("name.clean", matchQuery);
                matchClause.put("match", nameCleanMatch);
                shouldClauses.add(matchClause);
                
                // 店铺名召回不到数据后的兜底，但是不管有没有命中数据都会去查这个分类下的店铺，所以要降低权重
                Map<String, Object> categoryTermClause = new HashMap<>();
                Map<String, Object> categoryTerm = new HashMap<>();
                Map<String, Object> categoryValue = new HashMap<>();
                categoryValue.put("value", 1);
                categoryValue.put("boost", 0);  // 不影响召回阶段的排序，在function_score中调整排序权重
                categoryTerm.put("category_id", categoryValue);
                categoryTermClause.put("term", categoryTerm);
                shouldClauses.add(categoryTermClause);
                
                Map<String, Object> functionFilter = new HashMap<>();
                Map<String, Object> filterTerm = new HashMap<>();
                Map<String, Object> termCategory = new HashMap<>();
                termCategory.put("category_id", 1);
                filterTerm.put("term", termCategory);
                functionFilter.put("filter", filterTerm);
                functionFilter.put("weight", 0.1);
                functions.add(functionFilter);
                
                subBoolQuery.put("should", shouldClauses);
                subBoolQuery.put("minimum_should_match", 1);
                boolClause.put("bool", subBoolQuery);
                mustClauses.add(boolClause);
            }

            if (StringUtils.isNotBlank(tag)) {
                Map<String, Object> termClause = new HashMap<>();
                Map<String, Object> tagMap = new HashMap<>();
                termClause.put("term", tagMap);
                tagMap.put("tags", tag);
                mustClauses.add(termClause);
            }
            return this;
        }
        
        /**
         * 添加商家状态过滤（seller_disabled_flag = 1）
         */
        public ESQueryBuilder sellerEnabledFilter() {
            Map<String, Object> termClause = new HashMap<>();
            Map<String, Object> term = new HashMap<>();
            term.put("seller_disabled_flag", 1);
            termClause.put("term", term);
            mustClauses.add(termClause);
            return this;
        }
        
        /**
         * 添加分类过滤
         */
        public ESQueryBuilder categoryFilter(Long categoryId) {
            if (categoryId != null) {
                Map<String, Object> termClause = new HashMap<>();
                Map<String, Object> term = new HashMap<>();
                term.put("category_id", categoryId);
                termClause.put("term", term);
                mustClauses.add(termClause);
            }
            return this;
        }
        
        /**
         * 添加地理位置gauss衰减函数
         */
        public ESQueryBuilder geoGaussDecay(BigDecimal latitude, BigDecimal longitude, Integer orderBy) {
            if (latitude != null && longitude != null) {
                Map<String, Object> gaussFunction = new HashMap<>();
                Map<String, Object> gauss = new HashMap<>();
                Map<String, Object> location = new HashMap<>();
                Integer distanceWeight = orderBy == null || orderBy == 0 ? 10 : 50;
                
                location.put("origin", latitude + "," + longitude);
                location.put("scale", "100km");
                location.put("offset", "0km");
                location.put("decay", 0.5);
                
                gauss.put("location", location);
                gaussFunction.put("gauss", gauss);
                gaussFunction.put("weight", distanceWeight);
                
                functions.add(gaussFunction);
            }
            return this;
        }
        
        /**
         * 添加评分因子函数
         */
        public ESQueryBuilder scoreFieldValueFactor() {
            Map<String, Object> scoreFunction = new HashMap<>();
            Map<String, Object> fieldValueFactor = new HashMap<>();
            fieldValueFactor.put("field", "score");
            
            scoreFunction.put("field_value_factor", fieldValueFactor);
            scoreFunction.put("weight", 0.2);
            
            functions.add(scoreFunction);
            return this;
        }
        
        /**
         * 添加haversin距离计算脚本字段
         */
        public ESQueryBuilder haversinDistanceField(BigDecimal latitude, BigDecimal longitude) {
            if (latitude != null && longitude != null) {
                Map<String, Object> distanceField = new HashMap<>();
                Map<String, Object> script = new HashMap<>();
                Map<String, Object> params = new HashMap<>();
                
                params.put("lat", latitude);
                params.put("lon", longitude);
                
                script.put("source", "haversin(lat,lon,doc['location'].lat,doc['location'].lon)");
                script.put("lang", "expression");
                script.put("params", params);
                
                distanceField.put("script", script);
                scriptFields.put("distance", distanceField);
            }
            return this;
        }
        
        /**
         * 构建最终的查询对象
         */
        public Map<String, Object> build() {
            Map<String, Object> finalQuery = new HashMap<>();
            
            // 设置_source字段
            finalQuery.put("_source", Arrays.asList("*"));
            
            // 设置function_score查询
            Map<String, Object> query = new HashMap<>();
            query.put("function_score", functionScoreQuery);
            finalQuery.put("query", query);
            
            // 设置script_fields
            if (!scriptFields.isEmpty()) {
                finalQuery.put("script_fields", scriptFields);
            }
            
            return finalQuery;
        }
        
        /**
         * 构建带分页的查询对象
         */
        public Map<String, Object> build(long current, long size) {
            Map<String, Object> finalQuery = build();

            Map<String, Object> groupByTags = new HashMap<>();
            Map<String, Object> terms = new HashMap<>();
            groupByTags.put("group_by_tags", terms);
            terms.put("terms", MapUtil.builder("field", "tags").build());
            finalQuery.put("aggs", groupByTags);
            
            finalQuery.put("from", (current - 1) * size);
            finalQuery.put("size", size);
            return finalQuery;
        }
    }
}
