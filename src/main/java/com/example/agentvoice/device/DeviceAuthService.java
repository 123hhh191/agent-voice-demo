package com.example.agentvoice.device;

import com.example.agentvoice.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import com.example.agentvoice.limit.SlidingWindowLimiter;
import org.springframework.transaction.annotation.Transactional;

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
/** Device credentials are loaded from protected process configuration; only token digests are persisted. */
public class DeviceAuthService {
    private static final Duration CHALLENGE_TTL=Duration.ofMinutes(2), TOKEN_TTL=Duration.ofHours(1);
    private final JdbcTemplate jdbc;
    private final SlidingWindowLimiter limiter;
    private final Map<String,byte[]> credentials;
    private final SecureRandom random=new SecureRandom();

    public DeviceAuthService(JdbcTemplate jdbc,SlidingWindowLimiter limiter,@Value("${app.device.credentials:}") String configuredCredentials) {
        this.jdbc=jdbc;this.limiter=limiter;
        this.credentials=parseCredentials(configuredCredentials);
    }

    /** 为已登记设备签发一次性随机挑战。 */
    public Challenge challenge(String deviceId,String sn) {
        var devices=jdbc.query("SELECT enabled FROM device_registry WHERE device_id=? AND sn=?",(rs,n)->rs.getBoolean(1),deviceId,sn);
        if(devices.isEmpty()||!devices.get(0)||!credentials.containsKey(deviceId)) throw unauthorized();
        try{if(!limiter.allow("device-auth:"+deviceId))throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"DEVICE_RATE_LIMITED","请求过于频繁");}catch(ApiException ex){throw ex;}catch(RuntimeException ex){throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"DEVICE_AUTH_UNAVAILABLE","设备认证暂不可用");}
        byte[] nonce=new byte[32]; random.nextBytes(nonce);
        String challengeId=UUID.randomUUID().toString(),nonceText=Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        Instant expires=Instant.now().plus(CHALLENGE_TTL);
        jdbc.update("INSERT INTO device_challenge(challenge_id,device_id,nonce_hash,expires_at) VALUES(?,?,?,?)",challengeId,deviceId,sha256(nonceText),Timestamp.from(expires));
        return new Challenge(challengeId,nonceText,expires.toString());
    }

    /** 验证挑战签名并签发短期设备访问令牌。 */
    @Transactional
    public Token token(TokenRequest request) {
        byte[] secret=credentials.get(request.deviceId());
        if(secret==null) throw unauthorized();
        var rows=jdbc.query("SELECT c.nonce_hash,c.expires_at,c.consumed_at,d.sn,d.enabled FROM device_challenge c JOIN device_registry d ON d.device_id=c.device_id WHERE c.challenge_id=? AND c.device_id=?",
                (rs,n)->new ChallengeRow(rs.getString(1),rs.getTimestamp(2).toInstant(),rs.getTimestamp(3),rs.getString(4),rs.getBoolean(5)),request.challengeId(),request.deviceId());
        if(rows.isEmpty())throw unauthorized();
        ChallengeRow row=rows.get(0);
        if(!row.enabled()||row.consumedAt()!=null||Instant.now().isAfter(row.expiresAt())||!row.sn().equals(request.sn())||Math.abs(Instant.now().getEpochSecond()-request.issuedAt())>120)throw unauthorized();
        if(!constantTimeHex(row.nonceHash(),sha256(request.nonce())))throw unauthorized();
        String canonical="v1\n"+request.deviceId()+"\n"+request.sn()+"\n"+request.challengeId()+"\n"+request.nonce()+"\n"+request.issuedAt();
        if(!constantTimeHex(hmac(secret,canonical),request.signature()))throw unauthorized();
        if(jdbc.update("UPDATE device_challenge SET consumed_at=CURRENT_TIMESTAMP(6) WHERE challenge_id=? AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP(6)",request.challengeId())!=1)throw unauthorized();
        String accessToken=randomToken(); Instant expires=Instant.now().plus(TOKEN_TTL);
        jdbc.update("INSERT INTO device_access_token(token_hash,device_id,expires_at) VALUES(?,?,?)",sha256(accessToken),request.deviceId(),Timestamp.from(expires));
        return new Token(accessToken,TOKEN_TTL.toSeconds(),"DEVICE_BOUND_REPLAY");
    }

    /** 校验访问令牌有效期和设备状态，返回设备 ID。 */
    public String authenticate(String bearer) {
        if(bearer==null||bearer.length()<32||bearer.length()>256)throw unauthorized();
        var rows=jdbc.query("SELECT t.device_id FROM device_access_token t JOIN device_registry d ON d.device_id=t.device_id WHERE t.token_hash=? AND t.revoked_at IS NULL AND t.expires_at>CURRENT_TIMESTAMP(6) AND d.enabled=TRUE",(rs,n)->rs.getString(1),sha256(bearer));
        if(rows.isEmpty())throw unauthorized(); return rows.get(0);
    }
    /** 返回指定设备的密钥副本，避免外部修改缓存内容。 */
    public byte[] credential(String deviceId){byte[] key=credentials.get(deviceId);if(key==null)throw unauthorized();return key.clone();}

    @Transactional public void revokeDevice(String deviceId) {
        var active=jdbc.query("SELECT active_turn_id FROM device_registry WHERE device_id=? FOR UPDATE",(rs,n)->rs.getString(1),deviceId);
        if(!active.isEmpty()&&active.get(0)!=null)jdbc.update("UPDATE voice_turn SET state='CANCELLED',completed_at=CURRENT_TIMESTAMP(6) WHERE turn_id=? AND state IN ('RECORDING','PROCESSING')",active.get(0));
        jdbc.update("UPDATE device_registry SET enabled=FALSE,active_turn_id=NULL,owner_version=owner_version+1 WHERE device_id=?",deviceId);
        jdbc.update("UPDATE device_access_token SET revoked_at=CURRENT_TIMESTAMP(6) WHERE device_id=? AND revoked_at IS NULL",deviceId);
    }

    /** 解析环境变量注入的设备密钥映射。 */
    private Map<String,byte[]> parseCredentials(String input) {
        java.util.Map<String,byte[]> parsed=new java.util.HashMap<>();
        for(String item:input.split(";")) { if(item.isBlank())continue; int ix=item.indexOf('='); if(ix<1)throw new IllegalArgumentException("app.device.credentials must use deviceId=base64 pairs");
            String id=item.substring(0,ix).trim(); if(!id.matches("[A-Za-z0-9._:-]{1,96}")||parsed.containsKey(id))throw new IllegalArgumentException("invalid or duplicate device credential id");byte[] key=Base64.getDecoder().decode(item.substring(ix+1).trim()); if(key.length<32)throw new IllegalArgumentException("device HMAC credential must contain at least 256 bits"); parsed.put(id,key); }
        return Map.copyOf(parsed);
    }
    /** 清除过期挑战及过期或已撤销的访问令牌。 */
    @Scheduled(fixedDelayString="${app.device.cleanup-interval-ms:3600000}")
    public void cleanupExpired(){jdbc.update("DELETE FROM device_challenge WHERE expires_at<CURRENT_TIMESTAMP(6)");jdbc.update("DELETE FROM device_access_token WHERE expires_at<CURRENT_TIMESTAMP(6) OR revoked_at<CURRENT_TIMESTAMP(6)-INTERVAL 1 DAY");}
    private String randomToken(){byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private String hmac(byte[] key,String value){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String sha256(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private boolean constantTimeHex(String expected,String actual){try{return MessageDigest.isEqual(HexFormat.of().parseHex(expected),HexFormat.of().parseHex(actual));}catch(Exception ex){return false;}}
    private ApiException unauthorized(){return new ApiException(HttpStatus.UNAUTHORIZED,"DEVICE_AUTH_FAILED","设备认证失败");}
    public record Challenge(String challengeId,String nonce,String expiresAt){}
    public record TokenRequest(String deviceId,String sn,String challengeId,String nonce,long issuedAt,String signature){}
    public record Token(String accessToken,long expiresIn,String resumePolicy){}
    private record ChallengeRow(String nonceHash,Instant expiresAt,Timestamp consumedAt,String sn,boolean enabled){}
}
