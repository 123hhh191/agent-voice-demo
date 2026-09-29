package com.example.agentvoice.voice;

import com.example.agentvoice.output.CommandDispatcher;
import com.example.agentvoice.output.DeviceCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** MySQL-backed receipt validation, transaction, and concurrency checks. */
@SpringBootTest
class PlaybackCommandIT {
    private static final long OWNER_VERSION = 7L;
    private final String deviceId = "it-playback-" + UUID.randomUUID();
    private final String serial = "PB-" + UUID.randomUUID();
    private final String turnId = UUID.randomUUID().toString();
    private final String commandId = UUID.randomUUID().toString();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private VoicePlaybackService playback;
    @Autowired private CommandDispatcher commands;

    @BeforeEach
    void createFixture() {
        jdbc.update("INSERT INTO device_registry(device_id,sn,credential_ref,owner_version) VALUES(?,?,?,?)", deviceId, serial, deviceId, OWNER_VERSION);
        jdbc.update("INSERT INTO voice_turn(turn_id,device_id,start_request_id,state,format_json,attempt_id,resume_token_hash,owner_version,deadline_at,final_text,final_version,completed_at) VALUES(?,?,?,'FINAL','{}',?,?,?,?,'fixture final',1,CURRENT_TIMESTAMP(6))",
                turnId, deviceId, "receipt-" + turnId, UUID.randomUUID().toString(), "0".repeat(64), OWNER_VERSION, Timestamp.from(Instant.now().plusSeconds(60)));
        jdbc.update("UPDATE device_registry SET active_turn_id=? WHERE device_id=?", turnId, deviceId);
        insertCommand(commandId, "PLAY_AUDIO", true, Instant.now().plusSeconds(30), false);
    }

    @AfterEach
    void removeFixture() {
        jdbc.update("UPDATE device_registry SET active_turn_id=NULL WHERE device_id=?", deviceId);
        jdbc.update("DELETE FROM device_command WHERE device_id=?", deviceId);
        jdbc.update("DELETE FROM dialogue_delivery WHERE device_id=?", deviceId);
        jdbc.update("DELETE FROM device_dialogue_session WHERE device_id=?", deviceId);
        jdbc.update("DELETE FROM voice_turn WHERE device_id=?", deviceId);
        jdbc.update("DELETE FROM device_access_token WHERE device_id=?", deviceId);
        jdbc.update("DELETE FROM device_challenge WHERE device_id=?", deviceId);
        jdbc.update("DELETE FROM device_registry WHERE device_id=?", deviceId);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM device_registry WHERE device_id=?", Integer.class, deviceId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM voice_turn WHERE device_id=?", Integer.class, deviceId));
    }

    @Test
    void rejectsWrongCommandAndOldTurnWithoutChangingActiveTurn() {
        assertRejected(playback.complete(deviceId, turnId, OWNER_VERSION, UUID.randomUUID().toString()), "STALE_COMMAND");
        assertRejected(playback.complete(deviceId, UUID.randomUUID().toString(), OWNER_VERSION, commandId), "STALE_OWNER");
        assertStillActiveAndUnfinished();
    }

    @Test
    void rejectsOldOwnerUnacknowledgedAndExpiredCommands() {
        assertRejected(playback.complete(deviceId, turnId, OWNER_VERSION + 1, commandId), "STALE_OWNER");
        insertCommand(UUID.randomUUID().toString(), "PLAY_AUDIO", false, Instant.now().plusSeconds(30), false);
        String unackedId = jdbc.queryForObject("SELECT command_id FROM device_command WHERE device_id=? AND acked_at IS NULL", String.class, deviceId);
        assertRejected(playback.complete(deviceId, turnId, OWNER_VERSION, unackedId), "STALE_COMMAND");
        String expiredId = UUID.randomUUID().toString();
        insertCommand(expiredId, "PLAY_AUDIO", true, Instant.now().minusSeconds(1), false);
        assertRejected(playback.complete(deviceId, turnId, OWNER_VERSION, expiredId), "STALE_COMMAND");
        assertStillActiveAndUnfinished();
    }

    @Test
    void rejectsNonPlaybackCommand() {
        String errorCommandId = UUID.randomUUID().toString();
        insertCommand(errorCommandId, "ERROR", true, Instant.now().plusSeconds(30), false);
        assertRejected(playback.complete(deviceId, turnId, OWNER_VERSION, errorCommandId), "STALE_COMMAND");
        assertStillActiveAndUnfinished();
    }

    @Test
    void matchingReceiptCommitsAndDuplicateReturnsAlreadyApplied() {
        assertEquals(new VoicePlaybackService.Completion(VoicePlaybackService.Result.APPLIED, null),
                playback.complete(deviceId, turnId, OWNER_VERSION, commandId));
        assertNull(activeTurn());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE command_id=? AND playback_finished_at IS NOT NULL", Integer.class, commandId));

        assertEquals(new VoicePlaybackService.Completion(VoicePlaybackService.Result.ALREADY_APPLIED, null),
                playback.complete(deviceId, turnId, OWNER_VERSION, commandId));
        assertNull(activeTurn());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE command_id=? AND playback_finished_at IS NOT NULL", Integer.class, commandId));
    }

    @Test
    void concurrentMatchingReceiptsApplyOnlyOnce() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> completeTogether(ready, start));
            var second = pool.submit(() -> completeTogether(ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var outcomes = java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(c -> c.result() == VoicePlaybackService.Result.APPLIED).count());
            assertEquals(1, outcomes.stream().filter(c -> c.result() == VoicePlaybackService.Result.ALREADY_APPLIED).count());
            assertNull(activeTurn());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE command_id=? AND playback_finished_at IS NOT NULL", Integer.class, commandId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void commandCacheIsClearedOnlyAfterDatabaseReceiptCommits() {
        String cachedCommandId = UUID.randomUUID().toString();
        commands.persist(new DeviceCommand(cachedCommandId, deviceId, turnId, DeviceCommand.Type.PLAY_AUDIO, 2,
                Instant.now().plusSeconds(30), java.util.Map.of("fixture", true)));
        assertTrue(commands.acknowledge(deviceId, turnId, cachedCommandId));
        assertTrue(commands.hasPendingPlayback(deviceId, turnId));

        assertEquals(VoicePlaybackService.Result.APPLIED, playback.complete(deviceId, turnId, OWNER_VERSION, cachedCommandId).result());
        commands.playbackCommitted(cachedCommandId);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE command_id=? AND playback_finished_at IS NOT NULL", Integer.class, cachedCommandId));
        assertFalse(commands.mayDispatch(cachedCommandId));
    }

    private VoicePlaybackService.Completion completeTogether(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("receipt test start timed out");
        return playback.complete(deviceId, turnId, OWNER_VERSION, commandId);
    }

    private void assertRejected(VoicePlaybackService.Completion completion, String code) {
        assertEquals(VoicePlaybackService.Result.REJECTED, completion.result());
        assertEquals(code, completion.errorCode());
    }

    private void assertStillActiveAndUnfinished() {
        assertEquals(turnId, activeTurn());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=? AND playback_finished_at IS NOT NULL", Integer.class, deviceId));
    }

    private String activeTurn() {
        return jdbc.queryForObject("SELECT active_turn_id FROM device_registry WHERE device_id=?", String.class, deviceId);
    }

    private void insertCommand(String id, String type, boolean acked, Instant deadline, boolean finished) {
        jdbc.update("INSERT INTO device_command(command_id,device_id,turn_id,event_seq,command_type,payload_json,deadline_at,acked_at,playback_finished_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id, deviceId, turnId, Math.abs(id.hashCode()) + 1L, type, "{}", Timestamp.from(deadline), acked ? Timestamp.from(Instant.now()) : null,
                finished ? Timestamp.from(Instant.now()) : null);
    }
}
