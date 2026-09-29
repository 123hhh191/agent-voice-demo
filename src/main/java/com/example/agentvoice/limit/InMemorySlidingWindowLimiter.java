package com.example.agentvoice.limit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.scheduling.annotation.Scheduled;

/** 单进程精确滑窗；队列仅能在对应 map key 的 compute 中访问。 */
public final class InMemorySlidingWindowLimiter implements SlidingWindowLimiter {
    private final ConcurrentHashMap<String, ArrayDeque<Long>> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final long windowNanos;
    private final LongSupplier clock;

    public InMemorySlidingWindowLimiter(int limit, Duration window) {
        this(limit, window, System::nanoTime);
    }

    InMemorySlidingWindowLimiter(int limit, Duration window, LongSupplier clock) {
        if (limit < 1 || window == null || window.isZero() || window.isNegative()) throw new IllegalArgumentException("limit/window must be positive");
        this.limit = limit;
        this.windowNanos = window.toNanos();
        this.clock = clock;
    }

    /** 原子清理过期请求并判断用户是否仍有配额。 */
    @Override public boolean allow(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        boolean[] allowed = new boolean[1];
        windows.compute(userId, (key, queue) -> {
            long now = clock.getAsLong();
            ArrayDeque<Long> q = queue == null ? new ArrayDeque<>() : queue;
            prune(q, now);
            if (q.size() >= limit) { allowed[0] = false; return q; }
            q.addLast(now);
            allowed[0] = true;
            return q;
        });
        return allowed[0];
    }

    @Scheduled(fixedDelayString = "${app.rate-limit.cleanup-interval:60000}")
    /** 删除已无窗口内请求的用户记录，限制内存占用。 */
    public void cleanup() {
        windows.forEach((key, ignored) -> windows.computeIfPresent(key, (k, q) -> {
            prune(q, clock.getAsLong());
            return q.isEmpty() ? null : q;
        }));
    }

    int trackedUsers() { return windows.size(); }
    /** 移除已离开滑动时间窗的请求时间戳。 */
    private void prune(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() >= windowNanos) q.removeFirst();
    }
}
