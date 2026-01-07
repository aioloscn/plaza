package com.aiolos.plaza.home.canal;

import com.aiolos.plaza.mapper.ShopMapper;
import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import dto.ShopDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CanalScheduling implements Runnable, ApplicationContextAware {
    
    private ApplicationContext applicationContext;

    @Resource
    private ShopMapper shopMapper;
    @Resource
    private CanalConnector canalConnector;
    @Resource
    private RestClient restClient;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    @Scheduled(fixedDelay = 100)    // 每隔100ms拉取一次数据
    public void run() {
        long batchId = -1;
        try {
            Message message = canalConnector.getWithoutAck(1000);   // 需要手动ack
            batchId = message.getId();
            List<CanalEntry.Entry> entries = message.getEntries();
            if (batchId == -1 || entries.isEmpty()) {
                return;
            }
            entries.forEach(entry -> {
                if (entry.getEntryType() == CanalEntry.EntryType.ROWDATA) {
                    String database = entry.getHeader().getSchemaName();
                    String table = entry.getHeader().getTableName();
                    try {
                        CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
                        CanalEntry.EventType eventType = rowChange.getEventType();
                        
                        rowChange.getRowDatasList().forEach(rowData -> {
                            try {
                                // 根据操作类型处理数据
                                switch (eventType) {
                                    case INSERT:
                                    case UPDATE:
                                        List<CanalEntry.Column> afterColumns = rowData.getAfterColumnsList();
                                        Map<String, String> afterColumnMap = afterColumns.stream()
                                            .collect(Collectors.toMap(CanalEntry.Column::getName, CanalEntry.Column::getValue));
                                        indexES(database, table, afterColumnMap, eventType);
                                        break;
                                    case DELETE:
                                        List<CanalEntry.Column> beforeColumns = rowData.getBeforeColumnsList();
                                        Map<String, String> beforeColumnMap = beforeColumns.stream()
                                            .collect(Collectors.toMap(CanalEntry.Column::getName, CanalEntry.Column::getValue));
                                        deleteFromES(database, table, beforeColumnMap);
                                        break;
                                    default:
                                        log.debug("忽略操作类型: {}", eventType);
                                        break;
                                }
                            } catch (Exception e) {
                                log.error("处理Canal数据变更失败, database: {}, table: {}, eventType: {}", 
                                    database, table, eventType, e);
                            }
                        });
                    } catch (InvalidProtocolBufferException e) {
                        log.error("解析Canal协议失败", e);
                        throw new RuntimeException(e);
                    }
                }
            });
            canalConnector.ack(batchId);
        } catch (Exception e) {
            log.error("Canal数据同步异常", e);
            if (batchId != -1) {
                canalConnector.rollback(batchId);
            }
        }
    }

    /**
     * 索引数据到ES
     */
    private void indexES(String database, String table, Map<String, String> columnMap, CanalEntry.EventType eventType) {
        if (!StringUtils.equals(database, "plaza")) {
            return;
        }
        
        try {
            List<ShopDTO> shopList = null;
            
            // 根据不同表的变更，查询相关的shop数据
            if (StringUtils.equals(table, "shop")) {
                Long shopId = Long.valueOf(columnMap.get("id"));
                shopList = shopMapper.listShops(shopId, null, null);
                log.info("shop表数据变更，shopId: {}, 操作类型: {}", shopId, eventType);
            } else if (StringUtils.equals(table, "category")) {
                Long categoryId = Long.valueOf(columnMap.get("id"));
                shopList = shopMapper.listShops(null, categoryId, null);
                log.info("category表数据变更，categoryId: {}, 操作类型: {}", categoryId, eventType);
            } else if (StringUtils.equals(table, "seller")) {
                Long sellerId = Long.valueOf(columnMap.get("id"));
                shopList = shopMapper.listShops(null, null, sellerId);
                log.info("seller表数据变更，sellerId: {}, 操作类型: {}", sellerId, eventType);
            } else {
                log.debug("忽略表: {}", table);
                return;
            }
            
            // 批量索引到ES
            if (shopList != null && !shopList.isEmpty()) {
                bulkIndexToES(shopList);
                log.info("成功同步{}条shop数据到ES", shopList.size());
            }
            
        } catch (Exception e) {
            log.error("索引数据到ES失败, database: {}, table: {}, columnMap: {}", 
                database, table, columnMap, e);
        }
    }
    
    /**
     * 从ES删除数据
     */
    private void deleteFromES(String database, String table, Map<String, String> columnMap) {
        if (!StringUtils.equals(database, "plaza") || !StringUtils.equals(table, "shop")) {
            return;
        }
        
        try {
            String shopId = columnMap.get("id");
            if (StringUtils.isBlank(shopId)) {
                log.warn("删除ES文档失败，shopId为空");
                return;
            }
            
            // 删除ES中的文档
            Request request = new Request("DELETE", "/shop/_doc/" + shopId);
            Response response = restClient.performRequest(request);
            
            if (response.getStatusLine().getStatusCode() == 200 || 
                response.getStatusLine().getStatusCode() == 404) {
                log.info("成功从ES删除shop文档，shopId: {}", shopId);
            } else {
                log.warn("删除ES文档响应异常，shopId: {}, 状态码: {}", 
                    shopId, response.getStatusLine().getStatusCode());
            }
            
        } catch (Exception e) {
            log.error("从ES删除数据失败, database: {}, table: {}, columnMap: {}", 
                database, table, columnMap, e);
        }
    }
    
    /**
     * 批量索引shop数据到ES
     */
    private void bulkIndexToES(List<ShopDTO> shopList) throws IOException {
        if (shopList == null || shopList.isEmpty()) {
            return;
        }
        
        StringBuilder bulkBody = new StringBuilder();
        
        for (ShopDTO shop : shopList) {
            // 构建索引操作的元数据
            Map<String, Object> indexMeta = new HashMap<>();
            Map<String, Object> indexAction = new HashMap<>();
            indexAction.put("_index", "shop");
            indexAction.put("_id", shop.getId().toString());
            indexMeta.put("index", indexAction);
            
            // 添加操作元数据行
            bulkBody.append(objectMapper.writeValueAsString(indexMeta)).append("\n");
            
            // 构建文档数据
            Map<String, Object> doc = buildESDocument(shop);
            
            // 添加文档数据行
            bulkBody.append(objectMapper.writeValueAsString(doc)).append("\n");
        }
        
        // 执行批量索引
        Request request = new Request("POST", "/_bulk");
        request.addParameter("refresh", "true"); // 立即刷新，使数据可搜索
        request.setJsonEntity(bulkBody.toString());
        
        Response response = restClient.performRequest(request);
        
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new RuntimeException("批量索引到ES失败，状态码: " + response.getStatusLine().getStatusCode());
        }
        
        log.debug("批量索引完成，共{}条记录", shopList.size());
    }
    
    /**
     * 构建ES文档数据
     */
    private Map<String, Object> buildESDocument(ShopDTO shop) {
        Map<String, Object> doc = new HashMap<>();
        
        doc.put("id", shop.getId());
        doc.put("name", shop.getName());
        doc.put("icon_url", shop.getIconUrl());
        doc.put("address", shop.getAddress());
        doc.put("category_id", shop.getCategoryId());
        doc.put("category_name", shop.getCategoryName());
        doc.put("score", shop.getScore());
        doc.put("per_capita_price", shop.getPerCapitaPrice());
        doc.put("tags", shop.getTags());
        doc.put("seller_id", shop.getSellerId());
        doc.put("seller_score", shop.getSellerScore());
        doc.put("seller_disabled_flag", shop.getSellerDisabledFlag());
        doc.put("status", shop.getStatus());
        doc.put("created_time", shop.getCreatedTime());
        doc.put("updated_time", shop.getUpdatedTime());
        
        // 地理位置信息
        if (shop.getLatitude() != null && shop.getLongitude() != null) {
            Map<String, Object> location = new HashMap<>();
            location.put("lat", shop.getLatitude());
            location.put("lon", shop.getLongitude());
            doc.put("location", location);
        }
        
        // 为搜索优化添加clean字段
        if (StringUtils.isNotBlank(shop.getName())) {
            Map<String, Object> nameField = new HashMap<>();
            nameField.put("clean", shop.getName());
            doc.put("name", nameField);
        }
        
        return doc;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
