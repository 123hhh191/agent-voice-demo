package com.example.agentvoice.asr;

/** 表示识别会话产生的分段、版本化结果或错误。 */
public record AsrEvent(String attemptId, String segmentId, long revision, Type type, String text, String errorCode) {
    public enum Type { PARTIAL, FINAL, ERROR }
}
