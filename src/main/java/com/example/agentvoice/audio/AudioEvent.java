package com.example.agentvoice.audio;

/** 网关完成设备身份验证和协议映射后产生的统一音频事件。 */
/** 统一表示设备上报的音频帧或控制事件。 */
public record AudioEvent(String deviceId, String turnId, String attemptId, Type type, long seq,
                         long captureOffsetMs, int durationMs, AudioFormat format, byte[] payload, boolean last) {
    public enum Type { START, FRAME, END }
    public AudioEvent {
        if (deviceId == null || turnId == null || attemptId == null || type == null || seq < 0) throw new IllegalArgumentException("invalid audio event");
        payload = payload == null ? new byte[0] : payload.clone();
    }
    @Override public byte[] payload() { return payload.clone(); }
}
