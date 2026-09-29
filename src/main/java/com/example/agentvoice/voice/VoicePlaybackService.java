package com.example.agentvoice.voice;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

/** Commits an authenticated playback receipt and releases its active turn atomically. */
@Service
@org.springframework.context.annotation.Profile("!scaffold")
public class VoicePlaybackService {
    private final JdbcTemplate jdbc;

    public VoicePlaybackService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Completion complete(String deviceId, String turnId, long ownerVersion, String commandId) {
        var devices = jdbc.query("SELECT enabled,active_turn_id,owner_version FROM device_registry WHERE device_id=? FOR UPDATE",
                (rs, n) -> new DeviceRow(rs.getBoolean(1), rs.getString(2), rs.getLong(3)), deviceId);
        if (devices.isEmpty() || !devices.get(0).enabled()) return rejected("STALE_OWNER");
        DeviceRow device = devices.get(0);

        var turns = jdbc.query("SELECT state,owner_version FROM voice_turn WHERE turn_id=? AND device_id=? FOR UPDATE",
                (rs, n) -> new TurnRow(rs.getString(1), rs.getLong(2)), turnId, deviceId);
        if (turns.isEmpty()) return rejected("STALE_OWNER");
        TurnRow turn = turns.get(0);

        var commands = jdbc.query("SELECT command_type,acked_at,deadline_at,playback_finished_at,deadline_at>CURRENT_TIMESTAMP(6) FROM device_command WHERE command_id=? AND device_id=? AND turn_id=? FOR UPDATE",
                (rs, n) -> new CommandRow(rs.getString(1), rs.getTimestamp(2), rs.getTimestamp(3), rs.getTimestamp(4), rs.getBoolean(5)),
                commandId, deviceId, turnId);
        if (commands.isEmpty()) return rejected("STALE_COMMAND");
        CommandRow command = commands.get(0);

        if (device.ownerVersion() != ownerVersion || turn.ownerVersion() != ownerVersion) return rejected("STALE_OWNER");
        if (!"FINAL".equals(turn.state())) return rejected("STALE_COMMAND");
        if (!"PLAY_AUDIO".equals(command.type()) || command.ackedAt() == null) return rejected("STALE_COMMAND");

        boolean ownsActiveTurn = turnId.equals(device.activeTurnId());
        if (!ownsActiveTurn) {
            if (device.activeTurnId() == null && command.playbackFinishedAt() != null) {
                return new Completion(Result.ALREADY_APPLIED, null);
            }
            return rejected("STALE_OWNER");
        }

        // Repair the old two-transaction completion window without replaying AUTO_LISTEN.
        if (command.playbackFinishedAt() != null) {
            releaseActiveTurn(deviceId, turnId, ownerVersion);
            return new Completion(Result.ALREADY_APPLIED, null);
        }
        if (!command.deadlineActive()) return rejected("STALE_COMMAND");

        int completed = jdbc.update("UPDATE device_command SET playback_finished_at=CURRENT_TIMESTAMP(6) WHERE command_id=? AND device_id=? AND turn_id=? AND command_type='PLAY_AUDIO' AND acked_at IS NOT NULL AND playback_finished_at IS NULL AND deadline_at>CURRENT_TIMESTAMP(6)",
                commandId, deviceId, turnId);
        if (completed != 1) return rejected("STALE_COMMAND");
        releaseActiveTurn(deviceId, turnId, ownerVersion);
        return new Completion(Result.APPLIED, null);
    }

    private void releaseActiveTurn(String deviceId, String turnId, long ownerVersion) {
        int released = jdbc.update("UPDATE device_registry SET active_turn_id=NULL WHERE device_id=? AND active_turn_id=? AND owner_version=?",
                deviceId, turnId, ownerVersion);
        if (released != 1) throw new IllegalStateException("active playback turn changed while locked");
    }

    private Completion rejected(String code) { return new Completion(Result.REJECTED, code); }

    public enum Result { APPLIED, ALREADY_APPLIED, REJECTED }
    public record Completion(Result result, String errorCode) { }
    private record DeviceRow(boolean enabled, String activeTurnId, long ownerVersion) { }
    private record TurnRow(String state, long ownerVersion) { }
    private record CommandRow(String type, Timestamp ackedAt, Timestamp deadlineAt, Timestamp playbackFinishedAt, boolean deadlineActive) { }
}
