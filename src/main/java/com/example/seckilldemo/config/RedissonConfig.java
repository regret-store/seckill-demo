package com.example.seckilldemo.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 单机模式，连接本地 Redis
        config.useSingleServer().setAddress("redis://localhost:6378");
        return Redisson.create(config);
    }
}