package com.example.agentvoice.limit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
public class LimiterConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.mode", havingValue = "redis")
    SlidingWindowLimiter redisSlidingWindowLimiter(StringRedisTemplate redis,
            @Value("${app.rate-limit.limit:30}") int limit,
            @Value("${app.rate-limit.window:1m}") Duration window,
            @Value("${app.rate-limit.tenant-id:default}") String tenantId) {
        return new RedisSlidingWindowLimiter(redis, limit, window.toMillis(), tenantId);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rate-limit.mode", havingValue = "memory", matchIfMissing = true)
    SlidingWindowLimiter inMemorySlidingWindowLimiter(
            @Value("${app.rate-limit.limit:30}") int limit,
            @Value("${app.rate-limit.window:1m}") Duration window) {
        return new InMemorySlidingWindowLimiter(limit, window);
    }
}
