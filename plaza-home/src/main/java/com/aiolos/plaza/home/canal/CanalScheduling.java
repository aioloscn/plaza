package com.aiolos.plaza.home.canal;

import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.mapper.ShopMapper;
import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.exception.CanalClientException;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.aiolos.plaza.dto.ShopDTO;
import com.aiolos.plaza.home.service.HomeShopService;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.util.EntityUtils;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CanalScheduling implements Runnable {

    // Canal Leader 租约锁
    private static final long CANAL_LEADER_LOCK_TTL_SECONDS = 30L;
    // 按 shopId 的细粒度锁
    private static final long SHOP_INDEX_LOCK_TTL_SECONDS = 5L;
    private static final int SHOP_INDEX_LOCK_MAX_RETRY_COUNT = 2;
    private static final long SHOP_INDEX_LOCK_RETRY_INTERVAL_MILLIS = 50L;
    private static final long SHOP_FULL_SYNC_LOCK_TTL_SECONDS = 1800L;
    private static final int FULL_SYNC_DEFAULT_BATCH_SIZE = 500;
    private static final int FULL_SYNC_MAX_BATCH_SIZE = 2000;
    private static final int FULL_SYNC_RUN_LOCK_MAX_RETRY_COUNT = 120;
    private static final long FULL_SYNC_RUN_LOCK_RETRY_INTERVAL_MILLIS = 100L;
    // 续约与安全释放脚本
    private static final DefaultRedisScript<Long> RENEW_LOCK_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>();
    // 异步续约线程池
    private static final ScheduledExecutorService LOCK_RENEW_EXECUTOR = Executors.newScheduledThreadPool(1);

    static {
        RENEW_LOCK_SCRIPT.setResultType(Long.class);
        RENEW_LOCK_SCRIPT.setScriptText("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], tonumber(ARGV[2])) else return 0 end");
        RELEASE_LOCK_SCRIPT.setResultType(Long.class);
        RELEASE_LOCK_SCRIPT.setScriptText("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end");
    }

    @Resource
    private ShopMapper shopMapper;
    @Resource
    private RestClient restClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private HomeShopService homeShopService;
    @Autowired
    private CanalClient canalClient;

    // 锁行为计数器（便于观察稳定性与冲突情况）
    private final AtomicLong runLockAcquireSuccessCount = new AtomicLong();
    private final AtomicLong runLockAcquireFailCount = new AtomicLong();
    private final AtomicLong shopLockAcquireSuccessCount = new AtomicLong();
    private final AtomicLong shopLockAcquireFailCount = new AtomicLong();
    private final AtomicLong renewSuccessCount = new AtomicLong();
    private final AtomicLong renewFailCount = new AtomicLong();
    private final AtomicLong releaseSuccessCount = new AtomicLong();
    private final AtomicLong releaseFailCount = new AtomicLong();
    private final AtomicLong metricsLogTrigger = new AtomicLong();
    // 连续空批次数，便于观察无数据与卡顿
    private final AtomicLong emptyBatchCount = new AtomicLong();
    // 当前节点持有的Canal Leader锁句柄
    private volatile LockHandle leaderLockHandle;

    @Override
    @Scheduled(fixedDelay = 100)    // 每隔100ms拉取一次数据
    public void run() {
        // 全量同步期间，增量任务直接跳过，避免与手动全量争抢同一把运行锁
        if (isFullSyncRunning()) {
            return;
        }
        // 单Leader消费：只有拿到Leader租约的节点才能连接并消费Canal
        // Leader宕机后其他节点尝试重新抢占Leader租约
        if (!ensureCanalLeader()) {
            runLockAcquireFailCount.incrementAndGet();
            // 未成为Leader的节点主动断开连接，确保单活消费
            canalClient.disconnectIfConnected();
            logLockMetricsIfNeeded();
            return;
        }
        runLockAcquireSuccessCount.incrementAndGet();

        long batchId = -1;
        CanalConnector connector = null;
        try {
            connector = canalClient.ensureConnected();
            Message message = connector.getWithoutAck(1000);   // 需要手动ack
            batchId = message.getId();
            List<CanalEntry.Entry> entries = message.getEntries();
            if (batchId == -1 || entries.isEmpty()) {
                long currentEmpty = emptyBatchCount.incrementAndGet();
                if (currentEmpty % 300 == 0) {
                    log.info("Canal连续空批次, count: {}", currentEmpty);
                }
                return;
            }
            emptyBatchCount.set(0);
            for (CanalEntry.Entry entry : entries) {
                processEntry(entry);
            }
            connector.ack(batchId);
        } catch (Exception e) {
            log.error("Canal数据同步异常", e);
            if (isCanalBatchConflict(e)) {
                handleCanalBatchConflict(connector);
                return;
            }
            if (batchId != -1 && connector != null) {
                try {
                    log.warn("准备回滚Canal批次, batchId: {}, reason: {}", batchId, e.getMessage());
                    connector.rollback(batchId);
                    log.warn("Canal批次回滚完成, batchId: {}", batchId);
                } catch (Exception rollbackEx) {
                    log.warn("Canal按batchId回滚失败, batchId: {}", batchId, rollbackEx);
                }
            }
        } finally {
            logLockMetricsIfNeeded();
        }
    }

    private void processEntry(CanalEntry.Entry entry) {
        if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
            return;
        }
        String database = entry.getHeader().getSchemaName();
        String table = entry.getHeader().getTableName();
        try {
            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            CanalEntry.EventType eventType = rowChange.getEventType();
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                processRowData(database, table, eventType, rowData);
            }
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException("解析Canal协议失败, table=" + table, e);
        }
    }

    private void processRowData(
            String database,
            String table,
            CanalEntry.EventType eventType,
            CanalEntry.RowData rowData
    ) {
        try {
            switch (eventType) {
                case INSERT:
                case UPDATE:
                    Map<String, String> afterColumnMap = rowData.getAfterColumnsList().stream()
                            .collect(Collectors.toMap(CanalEntry.Column::getName, CanalEntry.Column::getValue));
                    indexES(database, table, afterColumnMap, eventType);
                    break;
                case DELETE:
                    Map<String, String> beforeColumnMap = rowData.getBeforeColumnsList().stream()
                            .collect(Collectors.toMap(CanalEntry.Column::getName, CanalEntry.Column::getValue));
                    deleteFromES(database, table, beforeColumnMap);
                    break;
                default:
                    log.debug("忽略操作类型: {}", eventType);
                    break;
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    "处理Canal数据变更失败, table=" + table + ", eventType=" + eventType,
                    e
            );
        }
    }

    /**
     * 确保当前节点是Canal Leader
     */
    private boolean ensureCanalLeader() {
        LockHandle currentHandle = leaderLockHandle;
        if (isLeaderLockValid(currentHandle)) {
            return true;
        }
        clearStaleLeaderHandle(currentHandle);
        LockHandle newHandle = acquireLockWithRenewal(
                RedisKeyEnum.LOCK_CANAL_LEADER.getKey(),
                CANAL_LEADER_LOCK_TTL_SECONDS
        );
        if (newHandle == null) {
            return false;
        }
        leaderLockHandle = newHandle;
        log.info("当前节点成为Canal Leader");
        return true;
    }

    private boolean isLeaderLockValid(LockHandle lockHandle) {
        if (lockHandle == null) {
            return false;
        }
        String currentOwner = stringRedisTemplate.opsForValue().get(lockHandle.lockKey);
        return StringUtils.equals(currentOwner, lockHandle.lockOwner);
    }

    private void clearStaleLeaderHandle(LockHandle lockHandle) {
        if (lockHandle == null) {
            return;
        }
        if (lockHandle.renewTask != null) {
            lockHandle.renewTask.cancel(false);
        }
        leaderLockHandle = null;
    }

    /**
     * 识别Canal batch位点冲突异常
     */
    private boolean isCanalBatchConflict(Exception e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof CanalClientException && StringUtils.contains(current.getMessage(), "batchId")) {
                String message = current.getMessage();
                if (StringUtils.contains(message, "not the firstly")
                        || StringUtils.contains(message, "is not exist")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * batch位点冲突后的恢复流程
     * 先全量rollback，再重连并重新subscribe，避免多节点抢占后本地连接状态脏读
     */
    private void handleCanalBatchConflict(CanalConnector connector) {
        try {
            log.warn("检测到Canal batch位点冲突，开始恢复");
            if (connector != null) {
                connector.rollback();
            }
            canalClient.reconnect();
            log.warn("检测到Canal batch位点冲突，已执行rollback并重连");
        } catch (Exception recoverEx) {
            log.error("Canal batch位点冲突恢复失败", recoverEx);
        }
    }

    @PreDestroy
    public void onShutdown() {
        LockHandle currentHandle = leaderLockHandle;
        leaderLockHandle = null;
        releaseLock(currentHandle);
        canalClient.disconnectIfConnected();
    }

    /**
     * 手动触发店铺全量同步到ES
     */
    public Map<String, Object> manualFullSync(Integer batchSize) {
        int effectiveBatchSize = normalizeBatchSize(batchSize);
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();

        LockHandle fullSyncLockHandle = acquireLockWithRenewal(
                RedisKeyEnum.LOCK_SHOP_FULL_SYNC.getKey(),
                SHOP_FULL_SYNC_LOCK_TTL_SECONDS
        );
        if (fullSyncLockHandle == null) {
            result.put("success", false);
            result.put("message", "已有全量同步任务在执行，请稍后再试");
            result.put("batchSize", effectiveBatchSize);
            return result;
        }

        LockHandle runLockHandle = acquireLockWithRetryAndRenewal(
                RedisKeyEnum.LOCK_CANAL_RUN.getKey(),
                SHOP_FULL_SYNC_LOCK_TTL_SECONDS,
                FULL_SYNC_RUN_LOCK_MAX_RETRY_COUNT,
                FULL_SYNC_RUN_LOCK_RETRY_INTERVAL_MILLIS
        );
        if (runLockHandle == null) {
            releaseLock(fullSyncLockHandle);
            result.put("success", false);
            result.put("message", "获取增量任务锁超时，请稍后重试");
            result.put("batchSize", effectiveBatchSize);
            return result;
        }

        long lastId = 0L;
        int totalCount = 0;
        int successCount = 0;
        int failedCount = 0;
        int batchCount = 0;
        try {
            while (true) {
                List<ShopDTO> shopList = shopMapper.listShopsAfterId(lastId, effectiveBatchSize);
                if (shopList == null || shopList.isEmpty()) {
                    break;
                }

                batchCount++;
                totalCount += shopList.size();
                long startIdExclusive = lastId;
                lastId = shopList.get(shopList.size() - 1).getId();
                try {
                    bulkIndexToES(shopList);
                    for (ShopDTO shop : shopList) {
                        if (shop != null && shop.getId() != null) {
                            homeShopService.addShopToBloomFilter(shop.getId());
                        }
                    }
                    successCount += shopList.size();
                } catch (Exception e) {
                    failedCount += shopList.size();
                    log.error("手动全量同步单批次失败, batchNo: {}, startIdExclusive: {}, endId: {}, size: {}",
                            batchCount, startIdExclusive, lastId, shopList.size(), e);
                }
            }
            result.put("success", failedCount == 0);
            result.put("message", failedCount == 0 ? "全量同步完成" : "全量同步完成，存在失败批次");
            result.put("batchSize", effectiveBatchSize);
            result.put("batchCount", batchCount);
            result.put("totalCount", totalCount);
            result.put("successCount", successCount);
            result.put("failedCount", failedCount);
            result.put("costMs", System.currentTimeMillis() - start);
            log.info("手动全量同步结束, success: {}, batchCount: {}, total: {}, successCount: {}, failedCount: {}, costMs: {}",
                    failedCount == 0, batchCount, totalCount, successCount, failedCount, System.currentTimeMillis() - start);
            return result;
        } finally {
            releaseLock(runLockHandle);
            releaseLock(fullSyncLockHandle);
        }
    }

    private int normalizeBatchSize(Integer batchSize) {
        if (batchSize == null || batchSize <= 0) {
            return FULL_SYNC_DEFAULT_BATCH_SIZE;
        }
        return Math.min(batchSize, FULL_SYNC_MAX_BATCH_SIZE);
    }

    private boolean isFullSyncRunning() {
        String lockOwner = stringRedisTemplate.opsForValue().get(RedisKeyEnum.LOCK_SHOP_FULL_SYNC.getKey());
        return StringUtils.isNotBlank(lockOwner);
    }

    /**
     * 索引数据到ES
     */
    private void indexES(String database, String table, Map<String, String> columnMap, CanalEntry.EventType eventType) {
        if (!StringUtils.equals(database, "plaza")) {
            return;
        }

        try {
            List<ShopDTO> shopList;

            // 根据不同表的变更，查询相关的shop数据
            if (StringUtils.equals(table, "shop")) {
                Long shopId = Long.valueOf(columnMap.get("id"));
                shopList = shopMapper.listShops(shopId, null, null);
                log.info("shop表数据变更，shopId: {}, 操作类型: {}, 新数据: {}", shopId, eventType, columnMap);
            } else if (StringUtils.equals(table, "category")) {
                Long categoryId = Long.valueOf(columnMap.get("id"));
                shopList = shopMapper.listShops(null, categoryId, null);
                log.info("category表数据变更，categoryId: {}, 操作类型: {}, 新数据: {}", categoryId, eventType, columnMap);
            } else if (StringUtils.equals(table, "seller")) {
                Long sellerId = Long.valueOf(columnMap.get("id"));
                shopList = shopMapper.listShops(null, null, sellerId);
                log.info("seller表数据变更，sellerId: {}, 操作类型: {}, 新数据: {}", sellerId, eventType, columnMap);
            } else {
                log.debug("忽略表: {}", table);
                return;
            }

            if (shopList != null && !shopList.isEmpty()) {
                List<ShopDTO> shopsToIndex = new ArrayList<>();
                List<LockHandle> acquiredShopLocks = new ArrayList<>();
                try {
                    for (ShopDTO shop : shopList) {
                        if (shop == null || shop.getId() == null) {
                            continue;
                        }
                        // 保证同一shopId写入互斥，避免写入ES的同时其他业务修改了shop数据而感知不到
                        String lockKey = RedisKeyEnum.LOCK_SHOP_INDEX.getKey(shop.getId());
                        // shop级锁同样启用“重试 + 自动续约”
                        LockHandle lockHandle = acquireLockWithRetryAndRenewal(
                                lockKey,
                                SHOP_INDEX_LOCK_TTL_SECONDS,
                                SHOP_INDEX_LOCK_MAX_RETRY_COUNT,
                                SHOP_INDEX_LOCK_RETRY_INTERVAL_MILLIS
                        );
                        if (lockHandle != null) {
                            shopLockAcquireSuccessCount.incrementAndGet();
                            shopsToIndex.add(shop);
                            acquiredShopLocks.add(lockHandle);
                        } else {
                            shopLockAcquireFailCount.incrementAndGet();
                            log.warn("获取shop索引锁失败，shopId: {}", shop.getId());
                        }
                    }
                    if (!shopsToIndex.isEmpty()) {
                        bulkIndexToES(shopsToIndex);
                        // 将新增或更新的shop同步添加到布隆过滤器
                        for (ShopDTO s : shopsToIndex) {
                            homeShopService.addShopToBloomFilter(s.getId());
                        }
                        log.info("成功同步{}条shop数据到ES，并更新布隆过滤器", shopsToIndex.size());
                    }
                } finally {
                    acquiredShopLocks.forEach(this::releaseLock);
                }
            }

        } catch (Exception e) {
            log.error("索引数据到ES失败, database: {}, table: {}, columnMap: {}",
                    database, table, columnMap, e);
            throw new RuntimeException("索引数据到ES失败, table=" + table, e);
        }
    }

    private String createLockOwner() {
        return UUID.randomUUID() + ":" + Thread.currentThread().getId();
    }

    // 获取锁并绑定续约任务，返回null表示获取失败
    private LockHandle acquireLockWithRenewal(String lockKey, long ttlSeconds) {
        String lockOwner = createLockOwner();
        if (!tryAcquireLock(lockKey, lockOwner, ttlSeconds)) {
            return null;
        }
        ScheduledFuture<?> renewTask = scheduleLockRenewal(lockKey, lockOwner, ttlSeconds);
        return new LockHandle(lockKey, lockOwner, renewTask);
    }

    private boolean tryAcquireLockWithRetry(
            String lockKey,
            String lockOwner,
            long ttlSeconds,
            int maxRetryCount,
            long retryIntervalMillis
    ) {
        for (int retry = 0; retry <= maxRetryCount; retry++) {
            if (tryAcquireLock(lockKey, lockOwner, ttlSeconds)) {
                return true;
            }
            if (retry < maxRetryCount) {
                try {
                    Thread.sleep(retryIntervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private boolean tryAcquireLock(String lockKey, String lockOwner, long ttlSeconds) {
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockOwner, ttlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    // 有限重试获取锁，成功后注册续约任务
    private LockHandle acquireLockWithRetryAndRenewal(
            String lockKey,
            long ttlSeconds,
            int maxRetryCount,
            long retryIntervalMillis
    ) {
        String lockOwner = createLockOwner();
        boolean acquired = tryAcquireLockWithRetry(lockKey, lockOwner, ttlSeconds, maxRetryCount, retryIntervalMillis);
        if (!acquired) {
            return null;
        }
        ScheduledFuture<?> renewTask = scheduleLockRenewal(lockKey, lockOwner, ttlSeconds);
        return new LockHandle(lockKey, lockOwner, renewTask);
    }

    // 续约频率约为TTL的1/3，降低误过期概率
    private ScheduledFuture<?> scheduleLockRenewal(String lockKey, String lockOwner, long ttlSeconds) {
        long renewIntervalMillis = Math.max(1000L, TimeUnit.SECONDS.toMillis(ttlSeconds) / 3);
        return LOCK_RENEW_EXECUTOR.scheduleAtFixedRate(
                () -> renewLock(lockKey, lockOwner, ttlSeconds),
                renewIntervalMillis,    // initialDelay
                renewIntervalMillis,    // period
                TimeUnit.MILLISECONDS
        );
    }

    // Lua续约：仅锁持有者可延长TTL
    private void renewLock(String lockKey, String lockOwner, long ttlSeconds) {
        try {
            Long renewed = stringRedisTemplate.execute(
                    RENEW_LOCK_SCRIPT,
                    Collections.singletonList(lockKey),
                    lockOwner,
                    String.valueOf(ttlSeconds)
            );
            if (Long.valueOf(1L).equals(renewed)) {
                renewSuccessCount.incrementAndGet();
            } else {
                renewFailCount.incrementAndGet();
            }
        } catch (Exception e) {
            renewFailCount.incrementAndGet();
            log.warn("锁续约失败，lockKey: {}", lockKey, e);
        }
    }

    // 安全释放：先停续约，再由Lua校验owner后删除
    private void releaseLock(LockHandle lockHandle) {
        if (lockHandle == null) {
            return;
        }
        if (lockHandle.renewTask != null) {
            lockHandle.renewTask.cancel(false);
        }
        Long released = stringRedisTemplate.execute(
                RELEASE_LOCK_SCRIPT,
                Collections.singletonList(lockHandle.lockKey),
                lockHandle.lockOwner
        );
        if (Long.valueOf(1L).equals(released)) {
            releaseSuccessCount.incrementAndGet();
        } else {
            releaseFailCount.incrementAndGet();
        }
    }

    // 每100次触发打印一次锁指标，避免日志过量
    private void logLockMetricsIfNeeded() {
        long current = metricsLogTrigger.incrementAndGet();
        if (current % 100 == 0) {
            log.info(
                    "锁统计 lockAcquireSuccess:{} lockAcquireFail:{} shopLockAcquireSuccess:{} shopLockAcquireFail:{} renewSuccess:{} renewFail:{} releaseSuccess:{} releaseFail:{}",
                    runLockAcquireSuccessCount.get(),
                    runLockAcquireFailCount.get(),
                    shopLockAcquireSuccessCount.get(),
                    shopLockAcquireFailCount.get(),
                    renewSuccessCount.get(),
                    renewFailCount.get(),
                    releaseSuccessCount.get(),
                    releaseFailCount.get()
            );
        }
    }

    // 统一封装锁上下文，确保续约与释放生命周期一致
    private static class LockHandle {
        private final String lockKey;
        private final String lockOwner;
        private final ScheduledFuture<?> renewTask;

        private LockHandle(String lockKey, String lockOwner, ScheduledFuture<?> renewTask) {
            this.lockKey = lockKey;
            this.lockOwner = lockOwner;
            this.renewTask = renewTask;
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
                log.info("成功从ES删除shop文档: {}", columnMap);
            } else {
                log.warn("删除ES文档响应异常，shopId: {}, 状态码: {}",
                        shopId, response.getStatusLine().getStatusCode());
            }

        } catch (Exception e) {
            log.error("从ES删除数据失败, database: {}, table: {}, columnMap: {}",
                    database, table, columnMap, e);
            throw new RuntimeException("从ES删除数据失败, table=" + table, e);
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

        String responseBody = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
        if (StringUtils.isNotBlank(responseBody)) {
            JsonNode root = objectMapper.readTree(responseBody);
            boolean hasErrors = root.path("errors").asBoolean(false);
            if (hasErrors) {
                int failedItems = 0;
                StringBuilder errorSamples = new StringBuilder();
                JsonNode items = root.path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        JsonNode indexNode = item.path("index");
                        int status = indexNode.path("status").asInt();
                        if (status >= 300) {
                            failedItems++;
                            if (errorSamples.length() < 1000) {
                                String failedId = indexNode.path("_id").asText();
                                String errorType = indexNode.path("error").path("type").asText();
                                String reason = indexNode.path("error").path("reason").asText();
                                errorSamples
                                        .append("[_id=").append(failedId)
                                        .append(",status=").append(status)
                                        .append(",type=").append(errorType)
                                        .append(",reason=").append(reason)
                                        .append("]");
                            }
                        }
                    }
                }
                log.error(
                        "ES批量索引存在失败, total: {}, failed: {}, samples: {}",
                        shopList.size(),
                        failedItems,
                        errorSamples
                );
                throw new RuntimeException(
                        "ES批量索引存在失败, total=" + shopList.size() + ", failed=" + failedItems + ", samples=" + errorSamples
                );
            }
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
        doc.put("description", shop.getDescription());
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

        return doc;
    }

}
