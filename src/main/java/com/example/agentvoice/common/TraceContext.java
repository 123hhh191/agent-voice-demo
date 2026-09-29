package com.example.agentvoice.common;

import java.util.UUID;

public final class TraceContext {
    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();
    private TraceContext() { }
    public static String current() { return TRACE.get(); }
    /** 设置当前线程的 traceId；无效或缺失时生成新 ID。 */
    public static String begin(String supplied) {
        String value = supplied != null && supplied.matches("[A-Za-z0-9._:-]{1,64}") ? supplied : UUID.randomUUID().toString();
        TRACE.set(value);
        return value;
    }
    public static void clear() { TRACE.remove(); }
}
