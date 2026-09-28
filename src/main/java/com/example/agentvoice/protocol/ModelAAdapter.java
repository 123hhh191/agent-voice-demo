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
    @Override public AudioEvent decodeAudio(String deviceId, byte[] bytes) {
        AudioFrameCodec.Frame f = AudioFrameCodec.decode(bytes);
        return new AudioEvent(deviceId, f.turnId().toString(), f.attemptId().toString(), AudioEvent.Type.FRAME, f.seq(), f.captureOffsetMs(), f.durationMs(),
                new com.example.agentvoice.audio.AudioFormat("PCM_S16LE", f.sampleRate(), f.channels(), 16), f.payload(), f.last());
    }
    @Override public byte[] encodeCommand(DeviceCommand c) { return c.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8); }
}
