package com.example.agentvoice.llm;

import java.util.List;

/** 保存一次模型决策中的文本、结束原因和工具调用。 */
public record LlmDecision(String content, String finishReason, List<ToolCall> toolCalls, String reasoningContent) {
    public record ToolCall(String id, String name, String argumentsJson) { }
    /** 判断模型本轮是否要求执行工具。 */
    public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
}
