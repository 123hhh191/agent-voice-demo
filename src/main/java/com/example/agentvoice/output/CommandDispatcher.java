package com.example.agentvoice.output;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 模拟器内命令去重与 ACK；生产可靠投递由 V004 存储记录兜底。 */
@Component
@org.springframework.context.annotation.Profile("!scaffold")
public final class CommandDispatcher {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    public CommandDispatcher(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    private final Map<String,DeviceCommand> pending=new ConcurrentHashMap<>();
    private final java.util.Set<String> acknowledged=ConcurrentHashMap.newKeySet();
    /** 持久化设备命令，并在事务提交后更新节点内待派发缓存。 */
    public void persist(DeviceCommand command) {
        if (command.deadline().isBefore(Instant.now())) throw new IllegalStateException("command expired");
        String payloadJson;
        try{payloadJson=mapper.writeValueAsString(command.payload());}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("command payload cannot be serialized",e);}
        boolean inserted=true;
        try{jdbc.update("INSERT INTO device_command(command_id,device_id,turn_id,event_seq,command_type,payload_json,deadline_at) VALUES(?,?,?,?,?,?,?)",command.commandId(),command.deviceId(),command.turnId(),command.eventSeq(),command.type().name(),payloadJson,java.sql.Timestamp.from(command.deadline()));}
        catch(org.springframework.dao.DuplicateKeyException duplicate){
            var rows=jdbc.query("SELECT device_id,turn_id,event_seq,command_type,payload_json,deadline_at,acked_at,playback_finished_at FROM device_command WHERE command_id=?",
                    (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant(),rs.getTimestamp(7)!=null,rs.getTimestamp(8)!=null},command.commandId());
            if(rows.isEmpty())throw new IllegalArgumentException("commandId conflict",duplicate);
            Object[] existing=rows.get(0);
            boolean same=command.deviceId().equals(existing[0])&&command.turnId().equals(existing[1])&&command.eventSeq()==(long)existing[2]
                    &&command.type().name().equals(existing[3])&&command.deadline().equals(existing[5])&&samePayload(payloadJson,(String)existing[4]);
            if(!same)throw new IllegalArgumentException("commandId reused with different command",duplicate);
            inserted=false;
            boolean alreadyAcked=(boolean)existing[6],alreadyFinished=(boolean)existing[7];
            afterCommit(()->{if(alreadyFinished)return;pending.putIfAbsent(command.commandId(),command);if(alreadyAcked)acknowledged.add(command.commandId());});
        }
        if(inserted)afterCommit(()->pending.putIfAbsent(command.commandId(),command));
    }
    public boolean acknowledge(String commandId){DeviceCommand command=pending.get(commandId);return command!=null&&acknowledge(command.deviceId(),command.turnId(),commandId);}
    /** 校验设备、轮次和截止时间后记录命令确认。 */
    public boolean acknowledge(String deviceId,String turnId,String commandId){
        DeviceCommand command=pending.get(commandId);if(command==null)command=findPending(deviceId,turnId);if(command==null||!command.commandId().equals(commandId)||!command.deviceId().equals(deviceId)||!command.turnId().equals(turnId))return false;if(acknowledged.contains(commandId))return true;
        int changed=jdbc.update("UPDATE device_command SET acked_at=CURRENT_TIMESTAMP(6) WHERE command_id=? AND acked_at IS NULL AND deadline_at>CURRENT_TIMESTAMP(6)",commandId);
        if(changed==1){acknowledged.add(commandId);return true;}
        Boolean already=jdbc.query("SELECT acked_at IS NOT NULL FROM device_command WHERE command_id=?",rs->rs.next()&&rs.getBoolean(1),commandId);
        if(Boolean.TRUE.equals(already)){acknowledged.add(commandId);return true;}return false;
    }
    public boolean mayDispatch(String commandId){DeviceCommand c=pending.get(commandId);if(c==null)c=findById(commandId);return c!=null&&!isAcknowledged(commandId)&&c.deadline().isAfter(Instant.now());}
    /** 从持久化记录恢复指定轮次尚未完成的命令。 */
    public DeviceCommand findPending(String deviceId,String turnId){
        var rows=jdbc.query("SELECT command_id,command_type,event_seq,payload_json,deadline_at,acked_at FROM device_command WHERE device_id=? AND turn_id=? AND playback_finished_at IS NULL AND deadline_at>CURRENT_TIMESTAMP(6) ORDER BY event_seq DESC LIMIT 1",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getLong(3),rs.getString(4),rs.getTimestamp(5).toInstant(),rs.getTimestamp(6)!=null},deviceId,turnId);
        if(rows.isEmpty())return null;Object[] r=rows.get(0);String id=(String)r[0];DeviceCommand command=pending.computeIfAbsent(id,k->{try{return new DeviceCommand(id,deviceId,turnId,DeviceCommand.Type.valueOf((String)r[1]),(long)r[2],(Instant)r[4],mapper.readValue((String)r[3],new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));}catch(Exception e){throw new IllegalStateException("stored command is invalid",e);}});if(Boolean.TRUE.equals(r[5]))acknowledged.add(id);return command;
    }
    public boolean hasPendingPlayback(String deviceId,String turnId){return !jdbc.query("SELECT command_id FROM device_command WHERE device_id=? AND turn_id=? AND command_type='PLAY_AUDIO' AND playback_finished_at IS NULL AND deadline_at>CURRENT_TIMESTAMP(6)",(rs,n)->rs.getString(1),deviceId,turnId).isEmpty();}
    /** Drops local command state only after VoicePlaybackService commits the durable receipt. */
    public void playbackCommitted(String commandId){pending.remove(commandId);acknowledged.remove(commandId);}
    private boolean isAcknowledged(String id){if(acknowledged.contains(id))return true;Boolean value=jdbc.query("SELECT acked_at IS NOT NULL FROM device_command WHERE command_id=?",rs->rs.next()&&rs.getBoolean(1),id);if(Boolean.TRUE.equals(value))acknowledged.add(id);return Boolean.TRUE.equals(value);}
    private boolean samePayload(String left,String right){try{return mapper.readTree(left).equals(mapper.readTree(right));}catch(Exception ex){return false;}}
    /** 仅在数据库事务成功后修改易失缓存。 */
    private void afterCommit(Runnable action){if(!TransactionSynchronizationManager.isSynchronizationActive()){action.run();return;}TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){action.run();}});}
    private DeviceCommand findById(String id){var rows=jdbc.query("SELECT device_id,turn_id,command_type,event_seq,payload_json,deadline_at,acked_at FROM device_command WHERE command_id=? AND playback_finished_at IS NULL AND deadline_at>CURRENT_TIMESTAMP(6)",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getString(5),rs.getTimestamp(6).toInstant(),rs.getTimestamp(7)!=null},id);if(rows.isEmpty())return null;Object[] r=rows.get(0);try{DeviceCommand c=new DeviceCommand(id,(String)r[0],(String)r[1],DeviceCommand.Type.valueOf((String)r[2]),(long)r[3],(Instant)r[5],mapper.readValue((String)r[4],new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));pending.putIfAbsent(id,c);if(Boolean.TRUE.equals(r[6]))acknowledged.add(id);return pending.get(id);}catch(Exception e){throw new IllegalStateException("stored command is invalid",e);}}
    @Scheduled(fixedDelayString="${app.voice.command-cleanup-interval-ms:60000}")
    /** 清理内存中的过期命令；持久化记录由数据库保留。 */
    public void cleanupExpired(){Instant now=Instant.now();pending.entrySet().removeIf(e->{if(e.getValue().deadline().isAfter(now))return false;acknowledged.remove(e.getKey());return true;});}
}
