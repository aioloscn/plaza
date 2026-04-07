package com.aiolos.plaza.order.domain.outbox;

import com.aiolos.plaza.enums.MqLocalMessageState;
import com.aiolos.plaza.enums.MqLocalMessageType;
import com.aiolos.plaza.model.po.MqLocalMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 统一创建本地消息，避免业务侧散落 `topic`、重试时间等字段默认值
 */
@Component
public class MqLocalMessageFactory {

    private static final int DEFAULT_MAX_RETRY_COUNT = 5;

    public MqLocalMessage build(String topic,
                                MqLocalMessageType messageType,
                                String businessKey,
                                String content) {
        return build(topic, messageType, businessKey, content, LocalDateTime.now(), null);
    }

    public MqLocalMessage build(String topic,
                                MqLocalMessageType messageType,
                                String businessKey,
                                String content,
                                LocalDateTime nextRetryTime) {
        return build(topic, messageType, businessKey, content, nextRetryTime, null);
    }

    public MqLocalMessage build(String topic,
                                MqLocalMessageType messageType,
                                String businessKey,
                                String content,
                                LocalDateTime nextRetryTime,
                                String tag) {
        LocalDateTime now = LocalDateTime.now();
        MqLocalMessage localMessage = new MqLocalMessage();
        localMessage.setTopic(topic);
        localMessage.setMessageType(messageType.getCode());
        localMessage.setContent(content);
        localMessage.setTag(tag);
        localMessage.setState(MqLocalMessageState.NEW.getCode());
        localMessage.setRetryCount(0);
        localMessage.setNextRetryTime(nextRetryTime == null ? now : nextRetryTime);
        localMessage.setMaxRetryCount(DEFAULT_MAX_RETRY_COUNT);
        localMessage.setBusinessKey(businessKey);
        localMessage.setFailReason(null);
        localMessage.setCreateTime(now);
        localMessage.setUpdateTime(now);
        return localMessage;
    }
}
