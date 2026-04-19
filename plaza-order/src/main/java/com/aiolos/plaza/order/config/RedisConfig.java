package com.aiolos.plaza.order.config;

import io.lettuce.core.api.StatefulConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.env.Environment;

@Configuration("plazaOrderRedisConfig")
public class RedisConfig {

    @Bean
    @Primary
    public LettuceConnectionFactory shopRedisConnectionFactory(Environment environment) {
        RedisProperties properties = bindRedisProperties(environment, "spring.data.redis.shop");
        return createConnectionFactory(properties);
    }

    @Bean(name = "shopRedisTemplate")
    @Primary
    public StringRedisTemplate shopRedisTemplate(@Qualifier("shopRedisConnectionFactory") LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public LettuceConnectionFactory orderRedisConnectionFactory(Environment environment) {
        RedisProperties properties = bindRedisProperties(environment, "spring.data.redis.cart");
        return createConnectionFactory(properties);
    }

    @Bean(name = "orderRedisTemplate")
    public StringRedisTemplate orderRedisTemplate(@Qualifier("orderRedisConnectionFactory") LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    private LettuceConnectionFactory createConnectionFactory(RedisProperties properties) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration();
        configuration.setHostName(properties.getHost());
        configuration.setPort(properties.getPort());
        configuration.setDatabase(properties.getDatabase());
        if (properties.getPassword() != null && !properties.getPassword().isEmpty()) {
            configuration.setPassword(RedisPassword.of(properties.getPassword()));
        }

        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        if (properties.getLettuce() != null && properties.getLettuce().getPool() != null) {
            poolConfig.setMaxTotal(properties.getLettuce().getPool().getMaxActive());
            poolConfig.setMaxIdle(properties.getLettuce().getPool().getMaxIdle());
            poolConfig.setMinIdle(properties.getLettuce().getPool().getMinIdle());
            if (properties.getLettuce().getPool().getMaxWait() != null) {
                poolConfig.setMaxWait(properties.getLettuce().getPool().getMaxWait());
            }
        }

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }

    private RedisProperties bindRedisProperties(Environment environment, String prefix) {
        return Binder.get(environment)
                .bind(prefix, Bindable.of(RedisProperties.class))
                .orElseGet(RedisProperties::new);
    }
}
