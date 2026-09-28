package com.example.agentvoice.asr;

public record AsrEvent(String attemptId, String segmentId, long revision, Type type, String text, String errorCode) {
    public enum Type { PARTIAL, FINAL, ERROR }
}
