package com.example.agentvoice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.example.agentvoice.config.DeepSeekProperties;

/** 阶段一 Agent 后端启动入口。 */
@SpringBootApplication
@EnableConfigurationProperties(DeepSeekProperties.class)
@EnableScheduling
public class AgentVoiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentVoiceApplication.class, args);
    }
}
