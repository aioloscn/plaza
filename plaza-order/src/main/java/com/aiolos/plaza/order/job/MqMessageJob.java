package com.aiolos.plaza.order.job;

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
    private static final int STATE_NEW = 0;
    private static final int STATE_SUCCESS = 1;
    private static final int STATE_FAIL = 2;
    private static final int STATE_PROCESSING = 3;
    private static final int MAX_RETRY_COUNT = 3;
    private static final int BATCH_SIZE = 50;
    private static final long CLAIM_TIMEOUT_SECONDS = 300;

    @Resource
    private MqLocalMessageService mqLocalMessageService;

    @Resource
    private StreamBridge streamBridge;

    /**
     * 扫描本地消息表并发送消息
     */
    @XxlJob("mqMessageJob")
    public void mqMessageJob() {
        // 1. 查询可处理消息：
        //    - 新建/失败消息可直接抢占
        //    - 处理中的消息超过抢占超时后允许接管（防止进程异常导致消息永远卡死）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime claimDeadline = now.minusSeconds(CLAIM_TIMEOUT_SECONDS);
        LambdaQueryWrapper<MqLocalMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.and(w -> w.in(MqLocalMessage::getState, STATE_NEW, STATE_FAIL)
                .or()
                .eq(MqLocalMessage::getState, STATE_PROCESSING).le(MqLocalMessage::getUpdateTime, claimDeadline));
        queryWrapper.lt(MqLocalMessage::getRetryCount, MAX_RETRY_COUNT);
        queryWrapper.orderByAsc(MqLocalMessage::getCreateTime);
        queryWrapper.last("LIMIT " + BATCH_SIZE);
        
        List<MqLocalMessage> messages = mqLocalMessageService.list(queryWrapper);
        
        if (messages == null || messages.isEmpty()) {
            return;
        }

        for (MqLocalMessage msg : messages) {
            // 2. 抢占消息：
            //    通过条件更新把状态置为“处理中”，只有更新成功的实例才允许发送，避免多实例重复发送同一条消息
            LocalDateTime claimTime = LocalDateTime.now();
            int claimed = mqLocalMessageService.getBaseMapper().update(null, new LambdaUpdateWrapper<MqLocalMessage>()
                    .set(MqLocalMessage::getState, STATE_PROCESSING)
                    .set(MqLocalMessage::getUpdateTime, claimTime)
                    .eq(MqLocalMessage::getId, msg.getId())
                    .lt(MqLocalMessage::getRetryCount, MAX_RETRY_COUNT)
                    .and(w -> w.in(MqLocalMessage::getState, STATE_NEW, STATE_FAIL)
                            .or()
                            .eq(MqLocalMessage::getState, STATE_PROCESSING).le(MqLocalMessage::getUpdateTime, claimDeadline)));
            if (claimed == 0) {
                continue;
            }

            try {
                // 3. 发送消息
                boolean sent = streamBridge.send(msg.getTopic(), MessageBuilder.withPayload(msg.getContent()).build());
                
                if (sent) {
                    // 4. 发送成功：仅允许把“当前占有中的消息”改为成功，避免并发覆盖
                    mqLocalMessageService.getBaseMapper().update(null, new LambdaUpdateWrapper<MqLocalMessage>()
                            .set(MqLocalMessage::getState, STATE_SUCCESS)
                            .set(MqLocalMessage::getUpdateTime, LocalDateTime.now())
                            .eq(MqLocalMessage::getId, msg.getId())
                            .eq(MqLocalMessage::getState, STATE_PROCESSING));
                } else {
                    // 4. 发送失败：回写失败状态并累加重试次数
                    mqLocalMessageService.getBaseMapper().update(null, new LambdaUpdateWrapper<MqLocalMessage>()
                            .set(MqLocalMessage::getState, STATE_FAIL)
                            .setSql("retry_count = retry_count + 1")
                            .set(MqLocalMessage::getUpdateTime, LocalDateTime.now())
                            .eq(MqLocalMessage::getId, msg.getId())
                            .eq(MqLocalMessage::getState, STATE_PROCESSING));
                    log.warn("MQ消息发送失败, id: {}, topic: {}", msg.getId(), msg.getTopic());
                }
            } catch (Exception e) {
                log.error("MQ消息发送异常, id: {}", msg.getId(), e);
                mqLocalMessageService.getBaseMapper().update(null, new LambdaUpdateWrapper<MqLocalMessage>()
                        .set(MqLocalMessage::getState, STATE_FAIL)
                        .setSql("retry_count = retry_count + 1")
                        .set(MqLocalMessage::getUpdateTime, LocalDateTime.now())
                        .eq(MqLocalMessage::getId, msg.getId())
                        .eq(MqLocalMessage::getState, STATE_PROCESSING));
            }
        }
    }
}
