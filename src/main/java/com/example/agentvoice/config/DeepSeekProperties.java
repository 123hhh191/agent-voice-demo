package com.example.agentvoice.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties("app.deepseek")
/** 集中保存模型端点、超时和 Agent 上下文限制。 */
public record DeepSeekProperties(
        URI baseUrl,
        String model,
        String apiKey,
        String thinkingMode,
        Duration connectTimeout,
        Duration readTimeout,
        @Min(1) @Max(8) int maxDecisions,
        @Min(1000) int contextSoftTokenBudget,
        @Min(1) @Max(8000) int maxInputChars,
        @Min(1) @Max(100) int maxPageSize) {
}
