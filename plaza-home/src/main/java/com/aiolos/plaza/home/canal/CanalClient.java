package com.aiolos.plaza.home.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

@Slf4j
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

    @Override
    public void destroy() throws Exception {
        disconnectIfConnected();
    }

    /**
     * 惰性创建连接
     * 仅Leader节点在真正消费前建立Canal连接
     */
    public synchronized CanalConnector ensureConnected() {
        if (connector != null) {
            return connector;
        }
        connector = CanalConnectors.newClusterConnector(
                Lists.newArrayList(new InetSocketAddress(host, port)),
                destination,
                username,
                password
        );
        connector.connect();
        // 指定filter，格式{database}.{table}
        connector.subscribe();
        log.info("Canal连接已建立, destination: {}", destination);
        return connector;
    }

    /**
     * 重连Canal并重新订阅
     * 多节点情况下用于位点异常后的自愈恢复
     */
    public synchronized void reconnect() {
        disconnectIfConnected();
        ensureConnected();
    }

    /**
     * 非Leader节点主动断开连接，避免多节点持有无效连接
     */
    public synchronized void disconnectIfConnected() {
        if (connector == null) {
            return;
        }
        try {
            connector.disconnect();
            log.info("Canal连接已断开, destination: {}", destination);
        } catch (Exception e) {
            log.warn("Canal断开连接失败，准备继续重连", e);
        } finally {
            connector = null;
        }
    }
}
