package com.example.agentvoice.limit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InMemorySlidingWindowLimiterTest {
    @Test void expiresAtExactWindowBoundary() {
        AtomicLong clock = new AtomicLong(10);
        InMemorySlidingWindowLimiter limiter = new InMemorySlidingWindowLimiter(1, Duration.ofNanos(100), clock::get);
        assertTrue(limiter.allow("u"));
        clock.addAndGet(99);
        assertFalse(limiter.allow("u"));
        clock.incrementAndGet();
        assertTrue(limiter.allow("u"));
    }

    @Test void concurrentRequestsNeverExceedLimit() throws Exception {
        int count = 100, limit = 7;
        var limiter = new InMemorySlidingWindowLimiter(limit, Duration.ofMinutes(1));
        var ready = new CountDownLatch(count); var start = new CountDownLatch(1); var admitted = new AtomicInteger();
        Thread[] threads = new Thread[count];
        for (int i=0;i<count;i++) {
            threads[i]=new Thread(() -> { ready.countDown(); try { start.await(); if(limiter.allow("same-user")) admitted.incrementAndGet(); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); } });
            threads[i].start();
        }
        ready.await(); start.countDown();
        for (Thread thread:threads) thread.join();
        assertEquals(limit, admitted.get());
    }

    @Test void cleanupReleasesIdleKeys() {
        AtomicLong clock = new AtomicLong();
        InMemorySlidingWindowLimiter limiter = new InMemorySlidingWindowLimiter(2, Duration.ofNanos(10), clock::get);
        assertTrue(limiter.allow("u"));
        assertEquals(1, limiter.trackedUsers());
        clock.set(10);
        limiter.cleanup();
        assertEquals(0, limiter.trackedUsers());
    }
}
