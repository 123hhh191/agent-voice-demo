package com.example.agentvoice.execution;

import org.springframework.stereotype.Component;

@Component
public class RetryPolicy {
    public enum Mode { SAFE, SAME_KEY_ONLY, NEVER }
    /** 根据失败类型、尝试次数和副作用策略判断是否重试。 */
    public boolean shouldRetry(Mode mode, boolean retryableFailure, int attempts, int maxAttempts) {
        return retryableFailure && attempts < maxAttempts && mode != Mode.NEVER;
    }
}
