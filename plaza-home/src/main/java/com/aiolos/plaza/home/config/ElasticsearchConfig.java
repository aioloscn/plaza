package com.aiolos.plaza.home.config;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {
    
    @Value("${spring.elasticsearch.host}")
    private String host;
    
    @Value("${spring.elasticsearch.port}")
    private int port;
    
    @Value("${spring.elasticsearch.username}")
    private String username;
    
    @Value("${spring.elasticsearch.password}")
    private String password;
    
    @Bean
    public RestClient restClient() {
        // 创建认证信息
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, 
            new UsernamePasswordCredentials(username, password));
        
        // 创建RestClient
        RestClientBuilder builder = RestClient.builder(
            new HttpHost(host, port, "http"))
            .setHttpClientConfigCallback(httpClientBuilder -> 
                httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        
        return builder.build();
    }
}