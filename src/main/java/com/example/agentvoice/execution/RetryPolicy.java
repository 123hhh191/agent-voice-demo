package com.example.agentvoice.execution;

import org.springframework.stereotype.Component;

@Component
public class RetryPolicy {
    public enum Mode { SAFE, SAME_KEY_ONLY, NEVER }
    public boolean shouldRetry(Mode mode, boolean retryableFailure, int attempts, int maxAttempts) {
        return retryableFailure && attempts < maxAttempts && mode != Mode.NEVER;
    }
}
