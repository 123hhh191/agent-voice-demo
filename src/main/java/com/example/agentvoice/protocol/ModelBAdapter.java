package com.example.agentvoice.protocol;

import com.example.agentvoice.audio.AudioEvent;
import com.example.agentvoice.output.DeviceCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.Base64;
import java.util.Map;

/** ModelB 使用 JSON + Base64 PCM 载荷；认证后的 deviceId 始终由网关注入。 */
@Component
public final class ModelBAdapter implements BoardProtocolAdapter {
    private final ObjectMapper mapper;
    public ModelBAdapter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override public String model() { return "MODEL_B"; }
    @Override public AudioEvent decodeAudio(String trustedDeviceId, byte[] wire) {
        try {
            if (wire == null || wire.length > 32_768) throw new IllegalArgumentException("ModelB frame exceeds wire limit");
            var n = mapper.readTree(wire);
            if (n.path("type").asText().equals("FRAME") == false) throw new IllegalArgumentException("only FRAME is supported");
            return new AudioEvent(trustedDeviceId, n.path("turnId").asText(), n.path("attemptId").asText(), AudioEvent.Type.FRAME,
                    n.path("seq").asLong(-1), n.path("captureOffsetMs").asLong(-1), n.path("durationMs").asInt(-1),
                    new com.example.agentvoice.audio.AudioFormat(n.path("codec").asText(), n.path("sampleRate").asInt(), n.path("channels").asInt(), n.path("bitsPerSample").asInt()),
                    Base64.getDecoder().decode(n.path("payload").asText()), n.path("last").asBoolean());
        } catch (Exception e) { if (e instanceof IllegalArgumentException iae) throw iae; throw new IllegalArgumentException("invalid ModelB audio event", e); }
    }
    @Override public byte[] encodeCommand(DeviceCommand command) { return command.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8); }
}
