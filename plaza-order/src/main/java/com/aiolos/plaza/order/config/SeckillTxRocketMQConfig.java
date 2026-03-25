package com.aiolos.plaza.order.config;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.spring.autoconfigure.RocketMQProperties;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQMessageConverter;
import org.apache.rocketmq.spring.support.RocketMQUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;

@Configuration
@Import(RocketMQAutoConfiguration.class)
public class SeckillTxRocketMQConfig {

    private static final String SECKILL_TX_PRODUCER_GROUP = "seckill-order-tx-producer-group";

    @Bean("seckillTxRocketMQTemplate")
    public RocketMQTemplate seckillTxRocketMQTemplate(RocketMQProperties rocketMQProperties,
                                                       RocketMQMessageConverter rocketMQMessageConverter) {
        RocketMQProperties.Producer producerConfig = rocketMQProperties.getProducer();
        DefaultMQProducer producer = RocketMQUtil.createDefaultMQProducer(
                SECKILL_TX_PRODUCER_GROUP,
                producerConfig.getAccessKey(),
                producerConfig.getSecretKey(),
                producerConfig.isEnableMsgTrace(),
                producerConfig.getCustomizedTraceTopic()
        );
        producer.setNamesrvAddr(rocketMQProperties.getNameServer());
        producer.setSendMsgTimeout(producerConfig.getSendMessageTimeout());
        producer.setCompressMsgBodyOverHowmuch(producerConfig.getCompressMessageBodyThreshold());
        producer.setRetryTimesWhenSendFailed(producerConfig.getRetryTimesWhenSendFailed());
        producer.setRetryTimesWhenSendAsyncFailed(producerConfig.getRetryTimesWhenSendAsyncFailed());
        producer.setRetryAnotherBrokerWhenNotStoreOK(producerConfig.isRetryNextServer());
        producer.setMaxMessageSize(producerConfig.getMaxMessageSize());

        RocketMQTemplate rocketMQTemplate = new RocketMQTemplate();
        rocketMQTemplate.setProducer(producer);
        rocketMQTemplate.setMessageConverter(rocketMQMessageConverter.getMessageConverter());
        return rocketMQTemplate;
    }
}
