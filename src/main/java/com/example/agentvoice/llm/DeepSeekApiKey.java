package com.example.agentvoice.llm;

import com.example.agentvoice.common.ApiException;
import org.springframework.http.HttpStatus;

/** Validates request credentials without retaining or including their value in errors. */
public final class DeepSeekApiKey {
    private DeepSeekApiKey() { }

    public static String requireValid(String value) {
        if (value == null || value.isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "DEEPSEEK_API_KEY_REQUIRED", "请先配置 DeepSeek API Key");
        String normalized = value.trim();
        if (normalized.length() > 512 || value.chars().anyMatch(Character::isISOControl)
                || normalized.chars().anyMatch(c -> c <= 32 || c > 126))
            throw new ApiException(HttpStatus.BAD_REQUEST, "DEEPSEEK_API_KEY_REQUIRED", "DeepSeek API Key 格式无效");
        return normalized;
    }
}
