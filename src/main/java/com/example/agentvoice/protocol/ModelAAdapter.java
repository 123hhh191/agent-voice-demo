package com.example.agentvoice.protocol;

import com.example.agentvoice.audio.AudioEvent;
import com.example.agentvoice.output.DeviceCommand;
import com.example.agentvoice.voice.AudioFrameCodec;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;

/** ModelA 沿用阶段三二进制帧头。 */
@Component
public final class ModelAAdapter implements BoardProtocolAdapter {
    @Override public String model() { return "MODEL_A"; }
    /** 将 ModelA 二进制帧转换为内部音频事件。 */
    @Override public AudioEvent decodeAudio(String deviceId, byte[] bytes) {
        AudioFrameCodec.Frame f = AudioFrameCodec.decode(bytes);
        return new AudioEvent(deviceId, f.turnId().toString(), f.attemptId().toString(), AudioEvent.Type.FRAME, f.seq(), f.captureOffsetMs(), f.durationMs(),
                new com.example.agentvoice.audio.AudioFormat("PCM_S16LE", f.sampleRate(), f.channels(), 16), f.payload(), f.last());
    }
    /** 将设备命令编码为 ModelA 线协议字节。 */
    @Override public byte[] encodeCommand(DeviceCommand c) { return c.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8); }
}
