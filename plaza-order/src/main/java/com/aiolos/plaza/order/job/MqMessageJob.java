package com.aiolos.plaza.order.job;

import com.aiolos.plaza.model.po.MqLocalMessage;
import com.aiolos.plaza.service.MqLocalMessageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    @Resource
    private MqLocalMessageService mqLocalMessageService;

    @Resource
    private StreamBridge streamBridge;

    /**
     * 扫描本地消息表并发送消息
     */
    @XxlJob("mqMessageJob")
    public void mqMessageJob() {
        // 1. 查询待发送的消息 (状态为0或2，且重试次数 < 3)
        // 限制每次处理 50 条
        LambdaQueryWrapper<MqLocalMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(MqLocalMessage::getState, 0, 2); // 0:新建, 2:失败
        queryWrapper.lt(MqLocalMessage::getRetryCount, 3);
        queryWrapper.orderByAsc(MqLocalMessage::getCreateTime);
        queryWrapper.last("LIMIT 50");
        
        List<MqLocalMessage> messages = mqLocalMessageService.list(queryWrapper);
        
        if (messages == null || messages.isEmpty()) {
            return;
        }

        for (MqLocalMessage msg : messages) {
            try {
                // 发送消息
                boolean sent = streamBridge.send(msg.getTopic(), MessageBuilder.withPayload(msg.getContent()).build());
                
                if (sent) {
                    msg.setState(1); // 发送成功
                    msg.setUpdateTime(LocalDateTime.now());
                } else {
                    msg.setState(2); // 发送失败
                    msg.setRetryCount(msg.getRetryCount() + 1);
                    msg.setUpdateTime(LocalDateTime.now());
                    log.warn("MQ消息发送失败, id: {}, topic: {}", msg.getId(), msg.getTopic());
                }
            } catch (Exception e) {
                log.error("MQ消息发送异常, id: {}", msg.getId(), e);
                msg.setState(2);
                msg.setRetryCount(msg.getRetryCount() + 1);
                msg.setUpdateTime(LocalDateTime.now());
            }
            // 更新状态
            mqLocalMessageService.updateById(msg);
        }
    }
}
