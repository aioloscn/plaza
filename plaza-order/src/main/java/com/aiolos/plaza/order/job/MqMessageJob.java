package com.aiolos.plaza.order.job;

import com.aiolos.plaza.enums.MqLocalMessageState;
import com.aiolos.plaza.model.po.MqLocalMessage;
import com.aiolos.plaza.service.MqLocalMessageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class MqMessageJob {
    private static final int BATCH_SIZE = 50;
    private static final long CLAIM_TIMEOUT_SECONDS = 300;
    private static final int[] RETRY_BACKOFF_MINUTES = {1, 5, 15, 30, 60};

    @Resource
    private MqLocalMessageService mqLocalMessageService;

    @Resource
    private StreamBridge streamBridge;

    /**
     * 扫描本地消息表并发送消息
     * 0/10 * * * * ?
     */
    @XxlJob("mqMessageJob")
    public void mqMessageJob() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime claimDeadline = now.minusSeconds(CLAIM_TIMEOUT_SECONDS);
        LambdaQueryWrapper<MqLocalMessage> queryWrapper = buildBasePendingQuery(now, claimDeadline);
        queryWrapper.orderByAsc(MqLocalMessage::getNextRetryTime).orderByAsc(MqLocalMessage::getCreateTime);
        queryWrapper.last("LIMIT " + BATCH_SIZE);
        processMessages(mqLocalMessageService.list(queryWrapper), claimDeadline);
    }

    private LambdaQueryWrapper<MqLocalMessage> buildBasePendingQuery(LocalDateTime now, LocalDateTime claimDeadline) {
        LambdaQueryWrapper<MqLocalMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.and(w -> w.in(MqLocalMessage::getState, MqLocalMessageState.NEW.getCode(), MqLocalMessageState.FAIL.getCode())
                .or()
                .eq(MqLocalMessage::getState, MqLocalMessageState.PROCESSING.getCode()).le(MqLocalMessage::getUpdateTime, claimDeadline));
        queryWrapper.and(w -> w.isNull(MqLocalMessage::getNextRetryTime).or().le(MqLocalMessage::getNextRetryTime, now));
        queryWrapper.apply("(max_retry_count IS NULL OR retry_count < max_retry_count)");
        return queryWrapper;
    }

    private void processMessages(List<MqLocalMessage> messages, LocalDateTime claimDeadline) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (MqLocalMessage msg : messages) {
            LocalDateTime claimTime = LocalDateTime.now();
            int claimed = claimMessage(msg, claimTime, claimDeadline);
            if (claimed == 0) {
                continue;
            }
            sendClaimedMessage(msg);
        }
    }

    private int claimMessage(MqLocalMessage msg, LocalDateTime claimTime, LocalDateTime claimDeadline) {
        LambdaUpdateWrapper<MqLocalMessage> claimWrapper = new LambdaUpdateWrapper<MqLocalMessage>()
                .set(MqLocalMessage::getState, MqLocalMessageState.PROCESSING.getCode())
                .set(MqLocalMessage::getUpdateTime, claimTime)
                .eq(MqLocalMessage::getId, msg.getId())
                .and(w -> w.in(MqLocalMessage::getState, MqLocalMessageState.NEW.getCode(), MqLocalMessageState.FAIL.getCode())
                        .or()
                        .eq(MqLocalMessage::getState, MqLocalMessageState.PROCESSING.getCode()).le(MqLocalMessage::getUpdateTime, claimDeadline))
                .and(w -> w.isNull(MqLocalMessage::getNextRetryTime).or().le(MqLocalMessage::getNextRetryTime, claimTime))
                .apply("(max_retry_count IS NULL OR retry_count < max_retry_count)");
        return mqLocalMessageService.getBaseMapper().update(null, claimWrapper);
    }

    private void sendClaimedMessage(MqLocalMessage msg) {
        try {
            boolean sent = streamBridge.send(msg.getTopic(), MessageBuilder.withPayload(msg.getContent()).build());
            if (sent) {
                markMessageSuccess(msg.getId());
                return;
            }
            markMessageFailed(msg, "streamBridge.send returned false");
            log.warn("MQ消息发送失败，id: {}, topic: {}", msg.getId(), msg.getTopic());
        } catch (Exception e) {
            log.error("MQ消息发送异常，id: {}", msg.getId(), e);
            markMessageFailed(msg, e.getMessage());
        }
    }

    private void markMessageSuccess(Long messageId) {
        mqLocalMessageService.getBaseMapper().update(null, new LambdaUpdateWrapper<MqLocalMessage>()
                .set(MqLocalMessage::getState, MqLocalMessageState.SUCCESS.getCode())
                .set(MqLocalMessage::getFailReason, null)
                .set(MqLocalMessage::getUpdateTime, LocalDateTime.now())
                .eq(MqLocalMessage::getId, messageId)
                .eq(MqLocalMessage::getState, MqLocalMessageState.PROCESSING.getCode()));
    }

    private void markMessageFailed(MqLocalMessage msg, String failReason) {
        int nextRetryCount = (msg.getRetryCount() == null ? 0 : msg.getRetryCount()) + 1;
        Integer maxRetryCount = msg.getMaxRetryCount();
        boolean dead = maxRetryCount != null && nextRetryCount >= maxRetryCount;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRetryTime = dead ? null : now.plusMinutes(resolveBackoffMinutes(nextRetryCount));

        mqLocalMessageService.getBaseMapper().update(null, new LambdaUpdateWrapper<MqLocalMessage>()
                .set(MqLocalMessage::getState, dead ? MqLocalMessageState.DEAD.getCode() : MqLocalMessageState.FAIL.getCode())
                .set(MqLocalMessage::getRetryCount, nextRetryCount)
                .set(MqLocalMessage::getNextRetryTime, nextRetryTime)
                .set(MqLocalMessage::getFailReason, truncateFailReason(failReason))
                .set(MqLocalMessage::getUpdateTime, now)
                .eq(MqLocalMessage::getId, msg.getId())
                .eq(MqLocalMessage::getState, MqLocalMessageState.PROCESSING.getCode()));
    }

    private long resolveBackoffMinutes(int retryCount) {
        int index = Math.max(0, Math.min(retryCount - 1, RETRY_BACKOFF_MINUTES.length - 1));
        return RETRY_BACKOFF_MINUTES[index];
    }

    private String truncateFailReason(String failReason) {
        if (failReason == null || failReason.isBlank()) {
            return null;
        }
        return failReason.length() <= 500 ? failReason : failReason.substring(0, 500);
    }
}
