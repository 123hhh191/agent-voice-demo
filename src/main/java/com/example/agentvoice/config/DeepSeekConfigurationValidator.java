package com.example.agentvoice.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!scaffold")
/** 启动时检查模型配置，避免请求阶段才发现缺少模型名。 */
public class DeepSeekConfigurationValidator {
    public DeepSeekConfigurationValidator(DeepSeekProperties properties) {
        if (properties.model() == null || properties.model().isBlank()) throw new IllegalStateException("必须配置环境变量 DEEPSEEK_MODEL");
    }
}
