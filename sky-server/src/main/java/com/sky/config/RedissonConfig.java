package com.sky.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 配置
 * <p>
 * 用单机模式连接当前 Redis，库号沿用 spring.redis.database（开发 db1 / 测试 db15）。
 * 只创建官方 RedissonClient 客户端，不引入 redisson-spring-boot-starter，
 * 避免与现有的 StringRedisTemplate / RedisTemplate 自动配置冲突。
 */
@Configuration
@Slf4j
public class RedissonConfig {

    @Value("${spring.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.redis.port:6379}")
    private int port;

    @Value("${spring.redis.database:1}")
    private int database;

    @Value("${spring.redis.password:}")
    private String password;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 降低资源占用：默认每个客户端 24 条连接 + 32 个 netty 线程，
        // 在多测试上下文/本机开发环境下开销过大，会拖慢其它 Redis 客户端
        config.setNettyThreads(2);
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4)
                .setSubscriptionConnectionPoolSize(1)
                .setTimeout(3000);
        if (StringUtils.hasText(password)) {
            config.useSingleServer().setPassword(password);
        }
        RedissonClient client = Redisson.create(config);
        log.info("[Redisson] 初始化完成, address=redis://{}:{}, database={}", host, port, database);
        return client;
    }
}
