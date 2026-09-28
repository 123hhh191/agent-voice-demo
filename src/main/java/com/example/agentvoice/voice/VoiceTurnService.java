package com.example.agentvoice.voice;

import com.example.agentvoice.common.ApiException;
import com.example.agentvoice.device.DeviceAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
@org.springframework.context.annotation.Profile("!scaffold")
/** Database-authoritative device/turn ownership and lifecycle transitions. */
public class VoiceTurnService {
    private static final Duration MAX_TURN=Duration.ofSeconds(30), RESUME_GRACE=Duration.ofSeconds(10);
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final DeviceAuthService auth;private final SecureRandom random=new SecureRandom();
    public VoiceTurnService(JdbcTemplate jdbc,ObjectMapper mapper,DeviceAuthService auth){this.jdbc=jdbc;this.mapper=mapper;this.auth=auth;}

    /** Creates an idempotent turn and atomically enforces one active turn per device. */
    @Transactional
    public Turn start(String deviceId,String requestId,AudioFormat format){
        validateFormat(format);
        var device=jdbc.query("SELECT active_turn_id FROM device_registry WHERE device_id=? AND enabled=TRUE FOR UPDATE",(rs,n)->rs.getString(1),deviceId);
        if(device.isEmpty())throw error(HttpStatus.UNAUTHORIZED,"DEVICE_AUTH_FAILED","设备认证失败");
        var previous=jdbc.query("SELECT turn_id,state,format_json,attempt_id,resume_token_hash,owner_version,deadline_at,final_text,disconnected_at FROM voice_turn WHERE device_id=? AND start_request_id=?",(rs,n)->new TurnRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6),rs.getTimestamp(7).toInstant(),rs.getString(8),rs.getTimestamp(9)==null?null:rs.getTimestamp(9).toInstant()),deviceId,requestId);
        if(!previous.isEmpty()){TurnRow row=previous.get(0);if(!row.formatJson().equals(write(format)))throw error(HttpStatus.CONFLICT,"REQUEST_ID_REUSED","clientRequestId 已用于不同音频格式");if("CANCELLED".equals(row.state())||"FAILED".equals(row.state()))throw error(HttpStatus.GONE,"TURN_TERMINAL","该请求对应轮次已结束，请使用新的 clientRequestId");if("RECORDING".equals(row.state())&&(Instant.now().isAfter(row.deadline())||(row.disconnectedAt()!=null&&Instant.now().isAfter(row.disconnectedAt().plus(RESUME_GRACE)))))throw error(HttpStatus.GONE,"RESUME_EXPIRED","轮次已超过恢复期限");if(device.get(0)!=null&&!row.turnId().equals(device.get(0)))throw error(HttpStatus.CONFLICT,"DEVICE_BUSY","设备已有其他活动轮次");return toTurn(deviceId,row);}
        if(device.get(0)!=null)throw error(HttpStatus.CONFLICT,"DEVICE_BUSY","设备已有活动轮次");
        UUID turn=UUID.randomUUID(),attempt=UUID.randomUUID();String resume=resumeToken(deviceId,turn.toString());Instant deadline=Instant.now().plus(MAX_TURN);
        jdbc.update("INSERT INTO voice_turn(turn_id,device_id,start_request_id,state,format_json,attempt_id,resume_token_hash,deadline_at) VALUES(?,?,?,'RECORDING',?,?,?,?)",turn.toString(),deviceId,requestId,write(format),attempt.toString(),sha256(resume),Timestamp.from(deadline));
        if(jdbc.update("UPDATE device_registry SET active_turn_id=?,owner_version=owner_version+1 WHERE device_id=? AND active_turn_id IS NULL",turn.toString(),deviceId)!=1)throw error(HttpStatus.CONFLICT,"DEVICE_BUSY","设备已有活动轮次");
        return new Turn(turn.toString(),attempt.toString(),resume,1,deadline.toString(),"RECORDING",null,"CONTINUE");
    }

    /** Rebinds a reconnecting socket; loss of node-local audio state always changes ASR attempt. */
    @Transactional
    public Turn resume(String deviceId,String turnId,String token,boolean localStateAvailable){
        if(!lockDeviceTurn(deviceId,turnId))throw error(HttpStatus.UNAUTHORIZED,"DEVICE_AUTH_FAILED","设备认证失败");
        var rows=jdbc.query("SELECT state,format_json,attempt_id,resume_token_hash,owner_version,started_at,deadline_at,final_text,disconnected_at FROM voice_turn WHERE turn_id=? AND device_id=? FOR UPDATE",(rs,n)->new ResumeRow(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getTimestamp(6).toInstant(),rs.getTimestamp(7).toInstant(),rs.getString(8),rs.getTimestamp(9)==null?null:rs.getTimestamp(9).toInstant()),turnId,deviceId);
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"TURN_NOT_FOUND","轮次不存在");ResumeRow r=rows.get(0);
        if(!constantEquals(r.tokenHash(),sha256(token==null?"":token)))throw error(HttpStatus.UNAUTHORIZED,"RESUME_EXPIRED","恢复凭据无效");
        if("FINAL".equals(r.state()))return new Turn(turnId,r.attemptId(),token,r.ownerVersion(),r.deadline().toString(),r.state(),r.finalText(),"RETURN_FINAL");
        if(!"RECORDING".equals(r.state())||Instant.now().isAfter(r.deadline())||(r.disconnectedAt()!=null&&Instant.now().isAfter(r.disconnectedAt().plus(RESUME_GRACE))))throw error(HttpStatus.GONE,"RESUME_EXPIRED","轮次已超过恢复期限");
        long owner=r.ownerVersion()+1;String attempt=r.attemptId();String strategy="CONTINUE";
        if(!localStateAvailable){attempt=UUID.randomUUID().toString();strategy="REQUIRE_FULL_REPLAY";}
        jdbc.update("UPDATE voice_turn SET owner_version=?,attempt_id=?,disconnected_at=NULL WHERE turn_id=? AND owner_version=?",owner,attempt,turnId,r.ownerVersion());
        jdbc.update("UPDATE device_registry SET owner_version=owner_version+1 WHERE device_id=? AND active_turn_id=?",deviceId,turnId);
        return new Turn(turnId,attempt,token,owner,r.deadline().toString(),r.state(),null,strategy);
    }

    /** Commits the unique final result only while this socket still owns an unexpired turn. */
    @Transactional
    public Turn restartForFullReplay(String deviceId,String turnId){
        if(!lockDeviceTurn(deviceId,turnId))throw error(HttpStatus.UNAUTHORIZED,"DEVICE_AUTH_FAILED","设备认证失败");
        var rows=jdbc.query("SELECT state,owner_version,deadline_at,resume_token_hash,final_text,disconnected_at FROM voice_turn WHERE turn_id=? AND device_id=? FOR UPDATE",(rs,n)->new Object[]{rs.getString(1),rs.getLong(2),rs.getTimestamp(3).toInstant(),rs.getString(4),rs.getString(5),rs.getTimestamp(6)==null?null:rs.getTimestamp(6).toInstant()},turnId,deviceId);
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"TURN_NOT_FOUND","轮次不存在");Object[] r=rows.get(0);
        Instant disconnected=(Instant)r[5];if(!"RECORDING".equals(r[0])||Instant.now().isAfter((Instant)r[2])||(disconnected!=null&&Instant.now().isAfter(disconnected.plus(RESUME_GRACE))))throw error(HttpStatus.GONE,"RESUME_EXPIRED","轮次已超过恢复期限");
        long owner=(long)r[1]+1;String attempt=UUID.randomUUID().toString();
        jdbc.update("UPDATE voice_turn SET owner_version=?,attempt_id=?,disconnected_at=NULL WHERE turn_id=? AND owner_version=?",owner,attempt,turnId,r[1]);
        jdbc.update("UPDATE device_registry SET owner_version=owner_version+1 WHERE device_id=? AND active_turn_id=?",deviceId,turnId);
        return new Turn(turnId,attempt,resumeToken(deviceId,turnId),owner,((Instant)r[2]).toString(),"RECORDING",null,"REQUIRE_FULL_REPLAY");
    }

    @Transactional
    public Turn persistFinal(String deviceId,String turnId,long ownerVersion,String finalText){
        if(!lockDeviceTurn(deviceId,turnId))throw error(HttpStatus.CONFLICT,"STALE_OWNER","设备或轮次归属已失效");
        int changed=jdbc.update("UPDATE voice_turn SET state='FINAL',final_text=?,final_version=final_version+1,completed_at=CURRENT_TIMESTAMP(6) WHERE turn_id=? AND device_id=? AND owner_version=? AND state IN ('RECORDING','PROCESSING') AND deadline_at>=CURRENT_TIMESTAMP(6)",finalText,turnId,deviceId,ownerVersion);
        if(changed==0){var saved=jdbc.query("SELECT state,final_text,attempt_id,deadline_at FROM voice_turn WHERE turn_id=? AND device_id=?",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant()},turnId,deviceId);if(!saved.isEmpty()&&"FINAL".equals(saved.get(0)[0]))return new Turn(turnId,(String)saved.get(0)[2],null,ownerVersion,((Instant)saved.get(0)[3]).toString(),"FINAL",(String)saved.get(0)[1],"RETURN_FINAL");throw error(HttpStatus.CONFLICT,"STALE_OWNER","该连接已失去轮次归属");}
        var info=jdbc.queryForMap("SELECT attempt_id,deadline_at FROM voice_turn WHERE turn_id=?",turnId);
        return new Turn(turnId,info.get("attempt_id").toString(),null,ownerVersion,((Timestamp)info.get("deadline_at")).toInstant().toString(),"FINAL",finalText,"FINAL_SAVED");
    }

    @Transactional public void acknowledgeFinal(String deviceId,String turnId,long ownerVersion){if(!lockDeviceTurn(deviceId,turnId))return;var rows=jdbc.query("SELECT state FROM voice_turn WHERE turn_id=? AND device_id=? AND owner_version=? FOR UPDATE",(rs,n)->rs.getString(1),turnId,deviceId,ownerVersion);if(!rows.isEmpty()&&"FINAL".equals(rows.get(0)))jdbc.update("UPDATE device_registry SET active_turn_id=NULL WHERE device_id=? AND active_turn_id=?",deviceId,turnId);}
    @Transactional public void cancel(String deviceId,String turnId,long ownerVersion){if(!lockDeviceTurn(deviceId,turnId))return;int changed=jdbc.update("UPDATE voice_turn SET state='CANCELLED',completed_at=CURRENT_TIMESTAMP(6) WHERE turn_id=? AND device_id=? AND owner_version=? AND state IN ('RECORDING','PROCESSING','FINAL')",turnId,deviceId,ownerVersion);if(changed==1)jdbc.update("UPDATE device_registry SET active_turn_id=NULL WHERE device_id=? AND active_turn_id=?",deviceId,turnId);}
    @Transactional public void fail(String deviceId,String turnId,long ownerVersion){if(!lockDeviceTurn(deviceId,turnId))return;int changed=jdbc.update("UPDATE voice_turn SET state='FAILED',completed_at=CURRENT_TIMESTAMP(6) WHERE turn_id=? AND device_id=? AND owner_version=? AND state IN ('RECORDING','PROCESSING')",turnId,deviceId,ownerVersion);if(changed==1)jdbc.update("UPDATE device_registry SET active_turn_id=NULL WHERE device_id=? AND active_turn_id=?",deviceId,turnId);}
    @Transactional public void failFinal(String deviceId,String turnId,long ownerVersion){if(!lockDeviceTurn(deviceId,turnId))return;int changed=jdbc.update("UPDATE voice_turn SET state='FAILED',completed_at=CURRENT_TIMESTAMP(6) WHERE turn_id=? AND device_id=? AND owner_version=? AND state='FINAL'",turnId,deviceId,ownerVersion);if(changed==1)jdbc.update("UPDATE device_registry SET active_turn_id=NULL WHERE device_id=? AND active_turn_id=?",deviceId,turnId);}
    @Transactional public boolean markDisconnected(String deviceId,String turnId,long ownerVersion){return jdbc.update("UPDATE voice_turn SET disconnected_at=COALESCE(disconnected_at,CURRENT_TIMESTAMP(6)) WHERE turn_id=? AND device_id=? AND owner_version=? AND state='RECORDING'",turnId,deviceId,ownerVersion)==1;}
    @Scheduled(fixedDelayString="${app.voice.cleanup-interval-ms:5000}")
    @Transactional public void expireUnavailableTurns(){
        var expired=jdbc.query("SELECT turn_id,device_id FROM voice_turn WHERE state='RECORDING' AND ((deadline_at<CURRENT_TIMESTAMP(6)-INTERVAL 10 SECOND) OR (disconnected_at<CURRENT_TIMESTAMP(6)-INTERVAL 10 SECOND))",(rs,n)->new String[]{rs.getString(1),rs.getString(2)});
        for(String[] item:expired){String turnId=item[0],deviceId=item[1];if(!lockDeviceTurn(deviceId,turnId))continue;int changed=jdbc.update("UPDATE voice_turn SET state='FAILED',completed_at=CURRENT_TIMESTAMP(6) WHERE turn_id=? AND device_id=? AND state='RECORDING' AND (deadline_at<CURRENT_TIMESTAMP(6)-INTERVAL 10 SECOND OR disconnected_at<CURRENT_TIMESTAMP(6)-INTERVAL 10 SECOND)",turnId,deviceId);if(changed==1)jdbc.update("UPDATE device_registry SET active_turn_id=NULL WHERE device_id=? AND active_turn_id=?",deviceId,turnId);}
    }
    public boolean ownsTurn(String deviceId,String turnId,long ownerVersion){return !jdbc.queryForList("SELECT v.turn_id FROM voice_turn v JOIN device_registry d ON d.device_id=v.device_id AND d.active_turn_id=v.turn_id WHERE v.turn_id=? AND v.device_id=? AND v.owner_version=? AND ((v.state IN ('RECORDING','PROCESSING') AND v.deadline_at>CURRENT_TIMESTAMP(6)) OR v.state='FINAL') AND d.enabled=TRUE",turnId,deviceId,ownerVersion).isEmpty();}

    private Turn toTurn(String deviceId,TurnRow row){String resume=resumeToken(deviceId,row.turnId());return new Turn(row.turnId(),row.attemptId(),resume,row.ownerVersion(),row.deadline().toString(),row.state(),row.finalText(),"IDEMPOTENT_REPLAY");}
    private boolean lockDeviceTurn(String deviceId,String turnId){var rows=jdbc.query("SELECT active_turn_id FROM device_registry WHERE device_id=? AND enabled=TRUE FOR UPDATE",(rs,n)->rs.getString(1),deviceId);return !rows.isEmpty()&&turnId.equals(rows.get(0));}
    private String resumeToken(String device,String turn){return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(auth.credential(device),"resume\n"+device+"\n"+turn));}
    private byte[] hmac(byte[] key,String value){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private boolean constantEquals(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private void validateFormat(AudioFormat f){if(f==null||f.codec()!=1||f.sampleRate()!=16000||f.channels()!=1||f.frameDurationMs()!=20)throw error(HttpStatus.BAD_REQUEST,"UNSUPPORTED_AUDIO_FORMAT","仅支持 PCM16、16kHz、单声道、20ms 帧");}
    private String write(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private ApiException error(HttpStatus status,String code,String message){return new ApiException(status,code,message);}
    private record TurnRow(String turnId,String state,String formatJson,String attemptId,String tokenHash,long ownerVersion,Instant deadline,String finalText,Instant disconnectedAt){}
    private record ResumeRow(String state,String formatJson,String attemptId,String tokenHash,long ownerVersion,Instant started,Instant deadline,String finalText,Instant disconnectedAt){}
    public record AudioFormat(int codec,int sampleRate,int channels,int frameDurationMs){}
    public record Turn(String turnId,String attemptId,String resumeToken,long ownerVersion,String deadlineAt,String state,String finalText,String resumeStrategy){}
}
