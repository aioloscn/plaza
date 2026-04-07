package com.aiolos.plaza.orderno.provider.rpc;

import com.aiolos.plaza.orderno.provider.api.OrderNoApi;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Value;

/**
 * 订单号服务实现：
 * 1) 使用 Snowflake 位段结构生成分布式唯一ID；
 * 2) 输出固定长度（前缀 + 19位数字）便于索引与排障；
 * 3) 内置时钟回拨保护，降低重复ID风险
 */
@DubboService
public class OrderNoApiImpl implements OrderNoApi {

    // 标准 Snowflake 位宽：时间戳 + 机房 + 机器 + 序列号
    private static final long WORKER_ID_BITS = 5L;                         // 机器位位宽：支持 0~31
    private static final long DATACENTER_ID_BITS = 5L;                     // 机房位位宽：支持 0~31
    private static final long SEQUENCE_BITS = 12L;                         // 毫秒内序列位：单机每毫秒最多 4096
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);    // 机器位最大值（31）
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS); // 机房位最大值（31）
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);     // 序列掩码（4095）
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;             // 机器位左移偏移（低位先放序列）
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS; // 机房位左移偏移
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS; // 时间戳左移偏移

    private final long workerId;              // 机器位（0~31）
    private final long datacenterId;          // 机房位（0~31）
    private final long epochMs;               // 自定义纪元起点，减少数字长度增长速度
    private final long maxClockBackwardMs;    // 允许的最大回拨毫秒数
    private long sequence = 0L;               // 同毫秒内自增序列
    private long lastTimestamp = -1L;         // 上次发号时间戳

    public OrderNoApiImpl(
            @Value("${order-no.instance-id:-1}") long instanceId,
            @Value("${order-no.worker-id:1}") long workerId,
            @Value("${order-no.datacenter-id:1}") long datacenterId,
            @Value("${order-no.epoch-ms:1704067200000}") long epochMs,
            @Value("${order-no.max-clock-backward-ms:3000}") long maxClockBackwardMs) {
        long finalWorkerId;
        long finalDatacenterId;
        // instance-id 优先，便于容器/集群统一分配，避免人工拆位配置冲突
        if (instanceId >= 0) {
            if (instanceId > 1023) {
                throw new IllegalArgumentException("order-no.instance-id must be between 0 and 1023");
            }
            finalWorkerId = instanceId & MAX_WORKER_ID;
            finalDatacenterId = (instanceId >> WORKER_ID_BITS) & MAX_DATACENTER_ID;
        } else {
            finalWorkerId = workerId;
            finalDatacenterId = datacenterId;
        }
        if (finalWorkerId < 0 || finalWorkerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("order-no.worker-id must be between 0 and 31");
        }
        if (finalDatacenterId < 0 || finalDatacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("order-no.datacenter-id must be between 0 and 31");
        }
        if (maxClockBackwardMs < 0) {
            throw new IllegalArgumentException("order-no.max-clock-backward-ms must be >= 0");
        }
        this.workerId = finalWorkerId;
        this.datacenterId = finalDatacenterId;
        this.epochMs = epochMs;
        this.maxClockBackwardMs = maxClockBackwardMs;
    }

    @Override
    public String nextParentOrderSn() {
        return buildOrderSn("P");
    }

    @Override
    public String nextChildOrderSn() {
        return buildOrderSn("D");
    }

    @Override
    public String nextSeckillOrderSn() {
        return buildOrderSn("S");
    }

    @Override
    public String nextOrderSn(String prefix) {
        // 自定义前缀归一化，避免空前缀和大小写不一致
        String normalizedPrefix = normalizePrefix(prefix);
        return buildOrderSn(normalizedPrefix);
    }

    private String buildOrderSn(String prefix) {
        // 固定19位数字，避免不同时间段出现长度跳变
        long id = nextId();
        return prefix + String.format("%019d", id);
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "O";
        }
        String trimmed = prefix.trim();
        if (trimmed.isEmpty()) {
            return "O";
        }
        return trimmed.toUpperCase();
    }

    private synchronized long nextId() {
        // 发号主流程：处理时钟回拨、同毫秒序列递增、跨毫秒序列归零
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            long diff = lastTimestamp - timestamp;
            if (diff > maxClockBackwardMs) {
                throw new IllegalStateException("Clock moved backwards, refusing to generate id");
            }
            sleepMillis(diff);
            timestamp = System.currentTimeMillis();
            if (timestamp < lastTimestamp) {
                throw new IllegalStateException("Clock moved backwards, refusing to generate id");
            }
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                timestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        // 位段拼装：时间差(高位) + 机房位 + 机器位 + 毫秒内序列(低位)
        return ((timestamp - epochMs) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitUntilNextMillis(long lastTs) {
        // 同毫秒序列耗尽后自旋到下一毫秒
        long ts = System.currentTimeMillis();
        while (ts <= lastTs) {
            ts = System.currentTimeMillis();
        }
        return ts;
    }

    private void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for clock recover", e);
        }
    }
}
