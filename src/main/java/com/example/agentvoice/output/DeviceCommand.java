package com.example.agentvoice.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;

/** 表示带设备、轮次、序号及有效期的下行命令。 */
public record DeviceCommand(String commandId,String deviceId,String turnId,Type type,long eventSeq,Instant deadline,Map<String,Object> payload) {
    public enum Type { PLAY_AUDIO, STOP_PLAYBACK, START_LISTENING, ERROR }
    public DeviceCommand { payload=Map.copyOf(payload); }
    /** 将命令序列化为设备协议载荷。 */
    public String toJson() {
        try { return new ObjectMapper().findAndRegisterModules().writeValueAsString(this); }
        catch (Exception e) { throw new IllegalStateException("command serialization failed",e); }
    }
}
