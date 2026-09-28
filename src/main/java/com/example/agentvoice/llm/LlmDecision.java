package com.example.agentvoice.llm;

import java.util.List;

public record LlmDecision(String content, String finishReason, List<ToolCall> toolCalls, String reasoningContent) {
    public record ToolCall(String id, String name, String argumentsJson) { }
    public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
}
