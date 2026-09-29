package com.example.agentvoice.limit;

/** 判断指定用户是否可以继续执行受限操作。 */
public interface SlidingWindowLimiter {
    boolean allow(String userId);
}
