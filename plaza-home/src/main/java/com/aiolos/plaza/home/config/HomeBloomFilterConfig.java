package com.aiolos.plaza.home.config;

import com.alibaba.google.common.hash.BloomFilter;
import com.alibaba.google.common.hash.Funnels;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HomeBloomFilterConfig {
    
    @Bean
    public BloomFilter<Long> homeBloomFilter() {
        // 预计最多存放10万个店铺ID，容错率0.01
        return BloomFilter.create(Funnels.longFunnel(), 100000, 0.01);
    }
}
