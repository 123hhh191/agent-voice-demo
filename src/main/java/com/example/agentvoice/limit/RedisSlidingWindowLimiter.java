package com.example.agentvoice.limit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.UUID;

/** Redis-backed exact sliding window. Redis failures propagate so callers can fail closed. */
public final class RedisSlidingWindowLimiter implements SlidingWindowLimiter {
    private final StringRedisTemplate redis;
    private final int limit;
    private final long windowMillis;
    private final String tenantId;
    private final DefaultRedisScript<Long> script;

    public RedisSlidingWindowLimiter(StringRedisTemplate redis, int limit, long windowMillis, String tenantId) {
        if (limit < 1 || windowMillis < 1) throw new IllegalArgumentException("limit/window must be positive");
        this.redis = redis; this.limit = limit; this.windowMillis = windowMillis; this.tenantId = tenantId;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("lua/sliding-window.lua"));
        this.script.setResultType(Long.class);
    }

    /** 通过 Redis Lua 原子维护用户窗口并检查配额。 */
    @Override public boolean allow(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        String key = "rate:{" + tenantId + ":" + userId + "}:dialogue";
        Long result = redis.execute(script, List.of(key), Integer.toString(limit), Long.toString(windowMillis), UUID.randomUUID().toString());
        if (result == null) throw new IllegalStateException("Redis limiter returned no result");
        return result == 1L;
    }
}
