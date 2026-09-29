package com.example.agentvoice.voice;

import com.example.agentvoice.device.DeviceAuthService;
import com.example.agentvoice.output.CommandDispatcher;
import com.example.agentvoice.output.DeviceCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies a busy second start over the real authenticated WebSocket endpoint. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceBusyWebSocketIT {
    private static final String DEVICE_ID = "it-busy-" + UUID.randomUUID();
    private static final String SERIAL = "SN-" + UUID.randomUUID();
    private static final byte[] DEVICE_KEY = randomKey();
    private static final String DEVICE_KEY_BASE64 = Base64.getEncoder().encodeToString(DEVICE_KEY);

    @DynamicPropertySource
    static void deviceCredentials(DynamicPropertyRegistry registry) {
        registry.add("app.device.credentials", () -> DEVICE_ID + "=" + DEVICE_KEY_BASE64);
    }

    @LocalServerPort
    private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeviceAuthService auth;
    @Autowired private VoiceTurnService turns;
    @Autowired private VoicePlaybackService playback;
    @Autowired private CommandDispatcher commands;
    @Autowired private ObjectMapper mapper;

    private WebSocket firstSocket;
    private WebSocket secondSocket;
    private String activeTurnId;

    @Test
    void secondStartReturnsDeviceBusyWithoutChangingFirstTurnOrItsBuffer() throws Exception {
        jdbc.update("INSERT INTO device_registry(device_id,sn,credential_ref) VALUES(?,?,?)", DEVICE_ID, SERIAL, DEVICE_ID);
        String token = authenticateDevice();

        var firstEvents = new EventListener();
        firstSocket = connect(token, firstEvents);
        firstSocket.sendText(startMessage("ws-first"), true).join();
        JsonNode started = firstEvents.nextEvent();
        assertEquals("started", started.path("type").asText());
        activeTurnId = started.path("turnId").asText();
        String attemptId = started.path("asrAttemptId").asText();
        var initialState = deviceState();
        assertEquals(activeTurnId, initialState.activeTurnId());

        sendFrame(firstSocket, activeTurnId, attemptId, 0);
        JsonNode firstAck = firstEvents.nextEvent();
        assertEquals("ack", firstAck.path("type").asText());
        assertEquals(0, firstAck.path("receivedThrough").asLong());

        var secondEvents = new EventListener();
        secondSocket = connect(token, secondEvents);
        secondSocket.sendText(startMessage("ws-second"), true).join();
        JsonNode rejected = secondEvents.nextEvent();
        assertEquals("error", rejected.path("type").asText());
        assertEquals("DEVICE_BUSY", rejected.path("code").asText());
        assertFalse(rejected.path("retryable").asBoolean());

        assertEquals(initialState, deviceState());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM voice_turn WHERE device_id=?", Integer.class, DEVICE_ID));

        sendFrame(firstSocket, activeTurnId, attemptId, 1);
        JsonNode retainedBufferAck = firstEvents.nextEvent();
        assertEquals("ack", retainedBufferAck.path("type").asText());
        assertEquals(1, retainedBufferAck.path("receivedThrough").asLong());
    }

    @Test
    void consecutiveTurnsUseTheLockedDeviceOwnerVersionForPlaybackReceipts() {
        jdbc.update("INSERT INTO device_registry(device_id,sn,credential_ref) VALUES(?,?,?)", DEVICE_ID, SERIAL, DEVICE_ID);
        var format = new VoiceTurnService.AudioFormat(1, 16000, 1, 20);

        var first = turns.start(DEVICE_ID, "ws-first-turn", format);
        assertEquals(first.ownerVersion(), deviceState().ownerVersion());
        assertEquals(first.ownerVersion(), turnOwnerVersion(first.turnId()));
        completePlayback(first);

        // AUTO_LISTEN uses the prior turn ID as the next start request ID.
        var second = turns.start(DEVICE_ID, "auto-" + first.turnId(), format);
        assertTrue(second.ownerVersion() > first.ownerVersion());
        assertEquals(second.ownerVersion(), deviceState().ownerVersion());
        assertEquals(second.ownerVersion(), turnOwnerVersion(second.turnId()));
        completePlayback(second);

        assertNull(deviceState().activeTurnId());
    }

    private void completePlayback(VoiceTurnService.Turn turn) {
        assertTrue(turns.beginFinalization(DEVICE_ID,turn.turnId(),turn.attemptId(),turn.ownerVersion()));
        turns.persistFinal(DEVICE_ID, turn.turnId(), turn.attemptId(),turn.ownerVersion(), "fixture final");
        String commandId = UUID.randomUUID().toString();
        commands.persist(new DeviceCommand(commandId, DEVICE_ID, turn.turnId(), DeviceCommand.Type.PLAY_AUDIO, 1,
                Instant.now().plusSeconds(30), Map.of("fixture", true)));
        assertTrue(commands.acknowledge(DEVICE_ID, turn.turnId(), commandId));
        assertEquals(VoicePlaybackService.Result.APPLIED,
                playback.complete(DEVICE_ID, turn.turnId(), turn.ownerVersion(), commandId).result());
        commands.playbackCommitted(commandId);
    }

    @AfterEach
    void cleanup() {
        if (firstSocket != null) {
            try {
                if (activeTurnId != null) firstSocket.sendText("{\"version\":1,\"type\":\"cancel\",\"payload\":{\"turnId\":\"" + activeTurnId + "\"}}", true).join();
            } catch (RuntimeException ignored) { }
            firstSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete");
        }
        if (secondSocket != null) secondSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete");
        jdbc.update("UPDATE device_registry SET active_turn_id=NULL WHERE device_id=?", DEVICE_ID);
        jdbc.update("DELETE FROM device_command WHERE device_id=?", DEVICE_ID);
        jdbc.update("DELETE FROM device_access_token WHERE device_id=?", DEVICE_ID);
        jdbc.update("DELETE FROM device_challenge WHERE device_id=?", DEVICE_ID);
        jdbc.update("DELETE FROM voice_turn WHERE device_id=?", DEVICE_ID);
        jdbc.update("DELETE FROM device_registry WHERE device_id=?", DEVICE_ID);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM device_registry WHERE device_id=?", Integer.class, DEVICE_ID));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM voice_turn WHERE device_id=?", Integer.class, DEVICE_ID));
    }

    private String authenticateDevice() {
        var challenge = auth.challenge(DEVICE_ID, SERIAL);
        long issuedAt = Instant.now().getEpochSecond();
        String canonical = "v1\n" + DEVICE_ID + "\n" + SERIAL + "\n" + challenge.challengeId() + "\n" + challenge.nonce() + "\n" + issuedAt;
        return auth.token(new DeviceAuthService.TokenRequest(DEVICE_ID, SERIAL, challenge.challengeId(), challenge.nonce(), issuedAt, hmac(canonical))).accessToken();
    }

    private WebSocket connect(String token, EventListener listener) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization", "Bearer " + token)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/device/voice"), listener)
                .get(10, TimeUnit.SECONDS);
    }

    private String startMessage(String requestId) throws Exception {
        return mapper.writeValueAsString(Map.of("version", 1, "type", "start", "clientRequestId", requestId,
                "payload", Map.of("format", Map.of("codec", 1, "sampleRate", 16000, "channels", 1, "frameDurationMs", 20))));
    }

    private void sendFrame(WebSocket socket, String turnId, String attemptId, long seq) {
        byte[] pcm = new byte[640];
        socket.sendBinary(ByteBuffer.wrap(AudioFrameCodec.encode(new AudioFrameCodec.Frame(
                UUID.fromString(turnId), UUID.fromString(attemptId), seq, seq * 20, 20, 1, 16000, 1, false, pcm))), true).join();
    }

    private DeviceState deviceState() {
        return jdbc.queryForObject("SELECT active_turn_id,owner_version FROM device_registry WHERE device_id=?",
                (rs, row) -> new DeviceState(rs.getString(1), rs.getLong(2)), DEVICE_ID);
    }

    private long turnOwnerVersion(String turnId) {
        return jdbc.queryForObject("SELECT owner_version FROM voice_turn WHERE turn_id=?", Long.class, turnId);
    }

    private String hmac(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(DEVICE_KEY, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private record DeviceState(String activeTurnId, long ownerVersion) { }

    private static final class EventListener implements WebSocket.Listener {
        private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (last) events.offer(data.toString());
            webSocket.request(1);
            return null;
        }
        JsonNode nextEvent() throws Exception {
            String event = events.poll(10, TimeUnit.SECONDS);
            assertNotNull(event, "Timed out waiting for WebSocket event");
            return new ObjectMapper().readTree(event);
        }
    }
}
