package com.aiolos.plaza.home.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.google.common.collect.Lists;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

@Component
public class CanalClient implements DisposableBean {
    
    private CanalConnector connector;
    
    @Value("${config.canal.host}")
    private String host;
    @Value("${config.canal.port}")
    private Integer port;
    @Value("${config.canal.destination}")
    private String destination;
    @Value("${config.canal.username}")
    private String username;
    @Value("${config.canal.password}")
    private String password;

    @Bean
    public CanalConnector getConnector() {
        connector = CanalConnectors.newClusterConnector(Lists.newArrayList(new InetSocketAddress(host, port)), destination, username, password);
        connector.connect();
        // 指定filter，格式{database}.{table}
        connector.subscribe();
        // 回滚寻找上次中断的位置
        connector.rollback();
        return connector;
    }
    
    @Override
    public void destroy() throws Exception {
        if (connector != null) {
            connector.disconnect();
        }
    }
}
