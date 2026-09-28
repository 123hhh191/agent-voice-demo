package com.example.agentvoice.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;

public record DeviceCommand(String commandId,String deviceId,String turnId,Type type,long eventSeq,Instant deadline,Map<String,Object> payload) {
    public enum Type { PLAY_AUDIO, STOP_PLAYBACK, START_LISTENING, ERROR }
    public DeviceCommand { payload=Map.copyOf(payload); }
    public String toJson() {
        try { return new ObjectMapper().findAndRegisterModules().writeValueAsString(this); }
        catch (Exception e) { throw new IllegalStateException("command serialization failed",e); }
    }
}
