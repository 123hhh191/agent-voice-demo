package com.example.agentvoice.dialogue;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 不调用外部 LLM 的可重复模拟响应。 */
@Component
@Profile("!scaffold")
public final class MockDialogueClient implements DialogueClient {
    private final JdbcTemplate jdbc;
    public MockDialogueClient(JdbcTemplate jdbc){this.jdbc=jdbc;}
    /** 幂等保存模拟对话结果，并校验轮次未被不同文本复用。 */
    @Override public DialogueReply submit(String deviceId,String sessionId,String turnId,String finalText) {
        String request="voice:"+turnId,response="已收到："+finalText;
        try{String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(finalText.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            jdbc.update("INSERT IGNORE INTO device_dialogue_session(device_id,session_id) VALUES(?,?)",deviceId,sessionId==null?java.util.UUID.randomUUID().toString():sessionId);
            String savedSessionId=jdbc.queryForObject("SELECT session_id FROM device_dialogue_session WHERE device_id=?",String.class,deviceId);
            if(sessionId!=null&&!sessionId.equals(savedSessionId))throw new IllegalStateException("dialogue session does not belong to device");
            jdbc.update("INSERT IGNORE INTO dialogue_delivery(turn_id,device_id,session_id,request_id,state,final_text_hash,response_text) VALUES(?,?,?,?,'SUCCEEDED',?,?)",turnId,deviceId,savedSessionId,request,hash,response);
            var saved=jdbc.queryForMap("SELECT session_id,state,final_text_hash,response_text FROM dialogue_delivery WHERE turn_id=?",turnId);
            if(!hash.equals(saved.get("final_text_hash")))throw new IllegalStateException("turnId reused with different final text");
            return new DialogueReply(request,saved.get("session_id").toString(),saved.get("response_text").toString(),saved.get("state").toString());
        }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("dialogue delivery persistence failed",e);}
    }
}
