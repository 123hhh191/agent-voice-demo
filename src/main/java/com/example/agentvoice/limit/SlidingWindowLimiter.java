package com.example.agentvoice.limit;

public interface SlidingWindowLimiter {
    boolean allow(String userId);
}
