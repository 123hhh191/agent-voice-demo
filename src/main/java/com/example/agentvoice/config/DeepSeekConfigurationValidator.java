package com.example.agentvoice.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!scaffold")
public class DeepSeekConfigurationValidator {
    public DeepSeekConfigurationValidator(DeepSeekProperties properties) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) throw new IllegalStateException("必须配置环境变量 DEEPSEEK_API_KEY");
        if (properties.model() == null || properties.model().isBlank()) throw new IllegalStateException("必须配置环境变量 DEEPSEEK_MODEL");
    }
}
