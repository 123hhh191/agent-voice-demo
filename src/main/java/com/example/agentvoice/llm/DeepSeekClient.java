package com.example.agentvoice.llm;

import com.example.agentvoice.common.ApiException;
import com.example.agentvoice.config.DeepSeekProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DeepSeekClient {
    private static final Logger log=LoggerFactory.getLogger(DeepSeekClient.class);
    private final DeepSeekProperties properties;
    private final ObjectMapper mapper;
    private final RestClient client;

    public DeepSeekClient(DeepSeekProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder().baseUrl(properties.baseUrl().toString()).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + properties.apiKey()).build();
    }

    public LlmDecision decide(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> request = Map.of("model", properties.model(), "messages", messages, "tools", tools,
                "tool_choice", "auto", "thinking", Map.of("type", "disabled"), "stream", false, "max_tokens", 4000);
        try {
            JsonNode root = client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                    .body(request).retrieve().body(JsonNode.class);
            return parse(root);
        } catch (RestClientException ex) {
            log.warn("DeepSeek request failed, traceId={}", com.example.agentvoice.common.TraceContext.current(), ex);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_UNAVAILABLE", "模型服务调用失败");
        }
    }

    public String summarize(List<Map<String,Object>> messages) {
        Map<String,Object> request=Map.of("model",properties.model(),"messages",messages,"stream",false,
                "thinking",Map.of("type","disabled"),"max_tokens",1200,"response_format",Map.of("type","json_object"));
        try {
            JsonNode root=client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
            JsonNode choice=root==null?null:root.path("choices");
            if(choice==null||!choice.isArray()||choice.isEmpty())throw new IllegalStateException("empty summary response");
            String result=choice.get(0).path("message").path("content").asText("");
            if(result.isBlank())throw new IllegalStateException("empty summary");
            return result;
        } catch(RestClientException ex){throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_UNAVAILABLE","模型摘要调用失败");}
    }

    LlmDecision parse(JsonNode root) {
        JsonNode choices = root == null ? null : root.path("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty() || !choices.get(0).path("message").isObject())
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_INVALID_RESPONSE", "模型返回内容不完整");
        JsonNode choice = choices.get(0), msg = choice.path("message");
        String finish = choice.path("finish_reason").asText("");
        if (!"assistant".equals(msg.path("role").asText()) || !msg.has("content") ||
                !(msg.get("content").isNull() || msg.get("content").isTextual()))
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_INVALID_RESPONSE", "模型消息结构无效");
        if (List.of("length", "aborted", "insufficient_system_resource").contains(finish))
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_INCOMPLETE", "模型输出未完成");
        List<LlmDecision.ToolCall> calls = new ArrayList<>();
        java.util.Set<String> ids=new java.util.HashSet<>();
        JsonNode toolCalls = msg.path("tool_calls");
        if (!toolCalls.isMissingNode() && !toolCalls.isNull() && !toolCalls.isArray())
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_INVALID_RESPONSE", "模型工具调用结构无效");
        if (toolCalls.isArray()) for (JsonNode call : toolCalls) {
            String id = call.path("id").asText(""), name = call.path("function").path("name").asText("");
            JsonNode args = call.path("function").path("arguments");
            if (id.isBlank() || id.length()>64 || !ids.add(id) || name.length()>128 || !name.matches("[A-Za-z0-9_]+") || !args.isTextual())
                throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_INVALID_TOOL_CALL", "模型工具调用结构无效");
            try { mapper.readTree(args.asText()); }
            catch (Exception e) { throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_INVALID_TOOL_ARGUMENTS", "模型工具参数不是有效 JSON"); }
            calls.add(new LlmDecision.ToolCall(id, name, args.asText()));
        }
        if (!calls.isEmpty() && !"tool_calls".equals(finish))
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_INVALID_RESPONSE", "模型工具调用未正常结束");
        String content = msg.path("content").isNull() ? null : msg.path("content").asText(null);
        String reasoning = msg.path("reasoning_content").asText(null);
        if (calls.isEmpty() && (content == null || content.isBlank() || !"stop".equals(finish)))
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_EMPTY_RESPONSE", "模型未返回可用答案");
        return new LlmDecision(content, finish, List.copyOf(calls), reasoning);
    }
}
