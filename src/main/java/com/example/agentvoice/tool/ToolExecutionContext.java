package com.example.agentvoice.tool;

/** 向工具传递调用者、会话和幂等操作范围。 */
public record ToolExecutionContext(String userId, String sessionId, String operationId) { }
