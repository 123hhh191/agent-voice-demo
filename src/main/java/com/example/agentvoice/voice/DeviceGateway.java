package com.example.agentvoice.voice;

import com.example.agentvoice.common.TraceContext;
import com.example.agentvoice.device.DeviceAuthService;
import com.example.agentvoice.protocol.BoardAdapterRegistry;
import com.example.agentvoice.output.CommandDispatcher;
import com.example.agentvoice.output.DeviceCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;

@Component
@org.springframework.context.annotation.Profile("!scaffold")
/** Routes authenticated control events and binary frames using bounded per-turn audio state. */
public class DeviceGateway extends AbstractWebSocketHandler {
    private final ObjectMapper mapper;private final VoiceTurnService turns;private final ResumeService resumeService;private final DeviceAuthService auth;private final VoiceTurnCoordinator coordinator;private final BoardAdapterRegistry boardAdapters;private final CommandDispatcher commands;private final String boardModel;
    private final ConcurrentHashMap<String,TurnState> states=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,WebSocketSession> deviceSockets=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,String> sessionTurns=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> sessionOwners=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,VoiceTurnService.AudioFormat> formats=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,String> turnDevices=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Instant> deadlines=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Instant> disconnectTimes=new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"voice-gap-timeout");t.setDaemon(true);return t;});
    private final AtomicInteger activeTurns=new AtomicInteger();private final int maxTurns,maxAudioBytes,reorderLimit;private final long gapWaitMs;
    private final Object[] deviceLocks=new Object[64];

    public DeviceGateway(ObjectMapper mapper,VoiceTurnService turns,ResumeService resumeService,DeviceAuthService auth,VoiceTurnCoordinator coordinator,BoardAdapterRegistry boardAdapters,CommandDispatcher commands,
            @Value("${app.voice.max-active-turns:64}") int maxTurns,@Value("${app.voice.max-audio-bytes:960000}") int maxAudioBytes,
            @Value("${app.voice.reorder-limit:64}") int reorderLimit,@Value("${app.voice.gap-wait-ms:1000}") long gapWaitMs,@Value("${app.voice.board-model:MODEL_A}") String boardModel){
        this.mapper=mapper;this.turns=turns;this.resumeService=resumeService;this.auth=auth;this.coordinator=coordinator;this.boardAdapters=boardAdapters;this.commands=commands;this.boardModel=boardModel;this.maxTurns=maxTurns;this.maxAudioBytes=maxAudioBytes;this.reorderLimit=reorderLimit;this.gapWaitMs=gapWaitMs;
        if(maxTurns<1||maxAudioBytes<640||reorderLimit<1||gapWaitMs<1)throw new IllegalArgumentException("voice limits must be positive and fit at least one PCM frame");
        java.util.Arrays.setAll(deviceLocks,i->new Object());
    }

    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message)throws Exception{
        String device=(String)session.getAttributes().get("deviceId");
        try{
            if(!device.equals(auth.authenticate((String)session.getAttributes().get("deviceToken"))))throw new ProtocolError("DEVICE_AUTH_FAILED",false);
            JsonNode root=mapper.readTree(message.getPayload());if(root.path("version").asInt()!=1)throw new ProtocolError("UNSUPPORTED_VERSION",false);
            String type=root.path("type").asText("");JsonNode payload=root.path("payload");
            // Only explicit control events can create, resume, close, or acknowledge a turn.
            switch(type){
                case "start" -> start(session,device,root,payload);
                case "resume" -> resume(session,device,payload);
                case "end" -> end(session,device,payload);
                case "cancel" -> cancel(session,device,payload);
                case "result_ack" -> acknowledgeFinal(session,device,payload);
                case "command_ack" -> acknowledgeCommand(session,device,payload);
                case "playback_finished" -> playbackFinished(session,device,payload);
                default -> throw new ProtocolError("UNKNOWN_EVENT",false);
            }
        }catch(ProtocolError ex){if("FORMAT_MISMATCH".equals(ex.code))failCurrent(session,device);sendError(session,ex.code,ex.retryable);if("DEVICE_AUTH_FAILED".equals(ex.code))session.close(CloseStatus.POLICY_VIOLATION);}
        catch(AudioFrameCodec.FrameException ex){failCurrent(session,device);sendError(session,ex.code(),false);}
        catch(Exception ex){sendError(session,"VOICE_OPERATION_FAILED",true);}
    }

    private void start(WebSocketSession session,String device,JsonNode root,JsonNode payload)throws Exception{synchronized(lockFor(device)){startLocked(session,device,root,payload);}}
    private void startLocked(WebSocketSession session,String device,JsonNode root,JsonNode payload)throws Exception{
        String requestId=root.path("clientRequestId").asText("");if(!requestId.matches("[A-Za-z0-9._:-]{1,128}"))throw new ProtocolError("INVALID_REQUEST",false);
        var format=mapper.treeToValue(payload.path("format"),VoiceTurnService.AudioFormat.class);
        var turn=turns.start(device,requestId,format);
        if("FINAL".equals(turn.state())){bind(session,device,turn.turnId(),turn.ownerVersion());send(session,Map.of("version",1,"type","final","turnId",turn.turnId(),"text",turn.finalText(),"finalVersion",1));return;}
        TurnState state=states.get(turn.turnId());
        if(state!=null&&"IDEMPOTENT_REPLAY".equals(turn.resumeStrategy())){turn=turns.resume(device,turn.turnId(),turn.resumeToken(),true);state.rebind(turn.ownerVersion());disconnectTimes.remove(turn.turnId());}
        if(state==null){if("IDEMPOTENT_REPLAY".equals(turn.resumeStrategy()))turn=turns.restartForFullReplay(device,turn.turnId());try{reserveState(turn);}catch(RuntimeException ex){turns.cancel(device,turn.turnId(),turn.ownerVersion());throw ex;}state=new TurnState(UUID.fromString(turn.turnId()),UUID.fromString(turn.attemptId()),turn.ownerVersion(),maxAudioBytes,reorderLimit,System.nanoTime());states.put(turn.turnId(),state);formats.put(turn.turnId(),format);turnDevices.put(turn.turnId(),device);deadlines.put(turn.turnId(),Instant.parse(turn.deadlineAt()));coordinator.restart(device,turn.turnId(),turn.attemptId(),"模拟识别输入");}
        bind(session,device,turn.turnId(),turn.ownerVersion());
        send(session,Map.of("version",1,"type","started","turnId",turn.turnId(),"asrAttemptId",turn.attemptId(),"resumeToken",turn.resumeToken(),"ownerVersion",turn.ownerVersion(),"deadlineAt",turn.deadlineAt(),"resumeStrategy",turn.resumeStrategy()));
    }

    private void resume(WebSocketSession session,String device,JsonNode payload)throws Exception{synchronized(lockFor(device)){resumeLocked(session,device,payload);}}
    private void resumeLocked(WebSocketSession session,String device,JsonNode payload)throws Exception{
        String turnId=payload.path("turnId").asText(""),token=payload.path("resumeToken").asText("");
        if(token.length()<40||token.length()>128)throw new ProtocolError("RESUME_EXPIRED",false);
        TurnState state=states.get(turnId);var resumed=resumeService.resume(device,turnId,token,state!=null);
        disconnectTimes.remove(turnId);
        WebSocketSession old=deviceSockets.put(device,session);if(old!=null&&old.isOpen()&&old!=session)old.close(CloseStatus.NORMAL.withReason("connection replaced"));
        if("RETURN_FINAL".equals(resumed.resumeStrategy())){bind(session,device,turnId,resumed.ownerVersion());var event=new java.util.LinkedHashMap<String,Object>();event.put("version",1);event.put("type","final");event.put("turnId",turnId);event.put("text",resumed.finalText());event.put("finalVersion",1);var command=coordinator.command(device,turnId);if(command==null)command=commands.findPending(device,turnId);if(command!=null&&commands.mayDispatch(command.commandId()))event.put("command",command);send(session,event);return;}
        if(state==null){try{reserveState(resumed);}catch(RuntimeException ex){turns.fail(device,turnId,resumed.ownerVersion());throw ex;}state=new TurnState(UUID.fromString(turnId),UUID.fromString(resumed.attemptId()),resumed.ownerVersion(),maxAudioBytes,reorderLimit,System.nanoTime());states.put(turnId,state);formats.put(turnId,new VoiceTurnService.AudioFormat(1,16000,1,20));turnDevices.put(turnId,device);deadlines.put(turnId,Instant.parse(resumed.deadlineAt()));coordinator.restart(device,turnId,resumed.attemptId(),"模拟识别输入");}else state.rebind(resumed.ownerVersion());
        bind(session,device,turnId,resumed.ownerVersion());
        var resumedEvent=new java.util.LinkedHashMap<String,Object>();resumedEvent.put("version",1);resumedEvent.put("type","resumed");resumedEvent.put("turnId",turnId);resumedEvent.put("asrAttemptId",resumed.attemptId());resumedEvent.put("ownerVersion",resumed.ownerVersion());resumedEvent.put("receivedThrough",state.receivedThrough());resumedEvent.put("missingRanges",state.missingRanges());resumedEvent.put("resumeStrategy",resumed.resumeStrategy());resumedEvent.put("deadlineAt",resumed.deadlineAt());var pendingCommand=commands.findPending(device,turnId);if(pendingCommand!=null&&commands.mayDispatch(pendingCommand.commandId()))resumedEvent.put("command",pendingCommand);send(session,resumedEvent);
    }

    private void end(WebSocketSession session,String device,JsonNode payload)throws Exception{
        String turnId=payload.path("turnId").asText("");TurnState state=requireOwner(session,device,turnId);JsonNode lastNode=payload.path("lastSeq");if(!lastNode.isIntegralNumber())throw new ProtocolError("INVALID_SEQUENCE",false);long last=lastNode.asLong(-1);state.end(last);
        sendAck(session,turnId,state);
        long ownerVersion=state.ownerVersion();
        if(state.claimFinalize())finish(session,device,state);else timer.schedule(()->{
            try{if(states.get(turnId)==state&&state.ownerVersion()==ownerVersion&&!state.endComplete()){turns.fail(device,turnId,ownerVersion);sendError(session,"MISSING_FRAMES",true);release(turnId);}}
            catch(Exception ignored){}
        },gapWaitMs,TimeUnit.MILLISECONDS);
    }

    private void binary(WebSocketSession session,BinaryMessage message)throws Exception{
        String turnId=sessionTurns.get(session.getId()),device=(String)session.getAttributes().get("deviceId");if(turnId==null)throw new ProtocolError("TURN_REQUIRED",false);
        TurnState state=requireOwner(session,device,turnId);if(coordinator.state(device)==VoiceTurnCoordinator.State.PLAYING)throw new ProtocolError("NOT_LISTENING",false);ByteBuffer source=message.getPayload().slice();byte[] bytes=new byte[source.remaining()];source.get(bytes);
        var mapped=boardAdapters.require(boardModel).decodeAudio(device,bytes);
        AudioFrameCodec.Frame frame=new AudioFrameCodec.Frame(UUID.fromString(mapped.turnId()),UUID.fromString(mapped.attemptId()),mapped.seq(),mapped.captureOffsetMs(),mapped.durationMs(),1,mapped.format().sampleRate(),mapped.format().channels(),mapped.last(),mapped.payload());VoiceTurnService.AudioFormat f=formats.get(turnId);
        if(!turnId.equals(mapped.turnId())||f==null||frame.codec()!=f.codec()||frame.sampleRate()!=f.sampleRate()||frame.channels()!=f.channels()||frame.durationMs()!=f.frameDurationMs())throw new ProtocolError("FORMAT_MISMATCH",false);
        var result=state.accept(frame);for(var contiguous:result.contiguous()){coordinator.accept(new com.example.agentvoice.audio.AudioEvent(device,turnId,frame.attemptId().toString(),com.example.agentvoice.audio.AudioEvent.Type.FRAME,contiguous.seq(),contiguous.captureOffsetMs(),contiguous.durationMs(),com.example.agentvoice.audio.AudioFormat.PCM16_MONO_16K,contiguous.payload(),contiguous.last()),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-state.startedNanos()));if(coordinator.state(device)==VoiceTurnCoordinator.State.FAILED){turns.fail(device,turnId,state.ownerVersion());release(turnId);sendError(session,"ASR_FAILED",false);return;}}send(session,Map.of("version",1,"type","ack","turnId",turnId,"receivedThrough",result.receivedThrough(),"missingRanges",result.missingRanges()));
        if(state.endComplete()&&state.claimFinalize())finish(session,device,state);
    }

    private void finish(WebSocketSession session,String device,TurnState state)throws Exception{
        String turnId=state.turnId().toString();if(!turns.ownsTurn(device,turnId,state.ownerVersion()))throw new ProtocolError("STALE_OWNER",false);
        coordinator.end(device,turnId,state.attemptId().toString());
        deliverFinal(session,device,state);
    }
    private void deliverFinal(WebSocketSession session,String device,TurnState state)throws Exception{
        String turnId=state.turnId().toString();
        if(coordinator.state(device)==VoiceTurnCoordinator.State.FAILED){String code=coordinator.failureCode(device);turns.fail(device,turnId,state.ownerVersion());release(turnId);sendError(session,code,false);return;}
        if(coordinator.state(device)==VoiceTurnCoordinator.State.DIALOGUE_PENDING){if(!turns.ownsTurn(device,turnId,state.ownerVersion()))throw new ProtocolError("STALE_OWNER",false);turns.persistFinal(device,turnId,state.ownerVersion(),coordinator.finalText(device,turnId));coordinator.deliverDialogue(device,turnId);if(coordinator.state(device)==VoiceTurnCoordinator.State.FAILED){turns.failFinal(device,turnId,state.ownerVersion());release(turnId);sendError(session,coordinator.failureCode(device),false);return;}}
        if(coordinator.state(device)!=VoiceTurnCoordinator.State.PLAYING||!state.claimDelivery())return;
        if(!turns.ownsTurn(device,turnId,state.ownerVersion()))throw new ProtocolError("STALE_OWNER",false);
        String text=coordinator.finalText(device,turnId);if(text==null)throw new ProtocolError("ASR_FINAL_MISSING",true);
        var command=coordinator.command(device,turnId);if(command==null)throw new ProtocolError("AUDIO_OUTPUT_FAILED",true);
        send(session,Map.of("version",1,"type","final","turnId",turnId,"text",text,"finalVersion",1,"asrAttemptId",state.attemptId().toString(),"command",command));
    }

    private void cancel(WebSocketSession session,String device,JsonNode payload)throws Exception{String id=payload.path("turnId").asText("");TurnState state=requireOwner(session,device,id);var stop=coordinator.cancel(device,id,state.attemptId().toString());turns.cancel(device,id,state.ownerVersion());release(id);var response=new java.util.LinkedHashMap<String,Object>();response.put("version",1);response.put("type","cancelled");response.put("turnId",id);if(stop!=null)response.put("command",stop);send(session,response);}
    private void acknowledgeFinal(WebSocketSession session,String device,JsonNode payload)throws Exception{String id=payload.path("turnId").asText("");long owner=owner(session,id);if(deviceSockets.get(device)!=session)throw new ProtocolError("STALE_OWNER",false);if(coordinator.state(device)==VoiceTurnCoordinator.State.PLAYING||commands.hasPendingPlayback(device,id))throw new ProtocolError("PLAYBACK_PENDING",false);turns.acknowledgeFinal(device,id,owner);release(id);send(session,Map.of("version",1,"type","acknowledged","turnId",id));}
    private void acknowledgeCommand(WebSocketSession session,String device,JsonNode payload)throws Exception{String id=payload.path("turnId").asText(""),commandId=payload.path("commandId").asText("");long owner=owner(session,id);if(deviceSockets.get(device)!=session)throw new ProtocolError("STALE_OWNER",false);if(coordinator.state(device)==VoiceTurnCoordinator.State.PLAYING)coordinator.commandAck(device,id,commandId);else if(!commands.acknowledge(device,id,commandId))throw new ProtocolError("COMMAND_EXPIRED",false);send(session,Map.of("version",1,"type","command_acknowledged","turnId",id,"commandId",commandId,"ownerVersion",owner));}
    private void playbackFinished(WebSocketSession session,String device,JsonNode payload)throws Exception{String id=payload.path("turnId").asText(""),commandId=payload.path("commandId").asText("");long owner=owner(session,id);if(deviceSockets.get(device)!=session)throw new ProtocolError("STALE_OWNER",false);if(coordinator.state(device)==VoiceTurnCoordinator.State.PLAYING)coordinator.playbackFinished(device,id,commandId);else commands.playbackFinished(device,id,commandId);turns.acknowledgeFinal(device,id,owner);release(id);if(coordinator.reopenPolicy()==VoiceTurnCoordinator.ReopenPolicy.AUTO_LISTEN){startAutoListen(session,device,id);}else send(session,Map.of("version",1,"type","playback_acknowledged","turnId",id,"commandId",commandId));}
    private void startAutoListen(WebSocketSession session,String device,String previousTurn)throws Exception{
        var format=new VoiceTurnService.AudioFormat(1,16000,1,20);var turn=turns.start(device,"auto-"+previousTurn,format);
        try{reserveState(turn);}catch(RuntimeException ex){turns.cancel(device,turn.turnId(),turn.ownerVersion());throw ex;}
        var state=new TurnState(UUID.fromString(turn.turnId()),UUID.fromString(turn.attemptId()),turn.ownerVersion(),maxAudioBytes,reorderLimit,System.nanoTime());
        states.put(turn.turnId(),state);formats.put(turn.turnId(),format);turnDevices.put(turn.turnId(),device);deadlines.put(turn.turnId(),Instant.parse(turn.deadlineAt()));coordinator.restart(device,turn.turnId(),turn.attemptId(),"模拟识别输入");bind(session,device,turn.turnId(),turn.ownerVersion());
        try{var command=new DeviceCommand(UUID.randomUUID().toString(),device,turn.turnId(),DeviceCommand.Type.START_LISTENING,1,Instant.parse(turn.deadlineAt()),Map.of("audioFormat",format,"resumePolicy","AUTO_LISTEN"));commands.persist(command);
            send(session,Map.of("version",1,"type","listening_started","turnId",turn.turnId(),"asrAttemptId",turn.attemptId(),"resumeToken",turn.resumeToken(),"ownerVersion",turn.ownerVersion(),"deadlineAt",turn.deadlineAt(),"command",command));
        }catch(Exception ex){turns.cancel(device,turn.turnId(),turn.ownerVersion());coordinator.cancel(device,turn.turnId(),turn.attemptId());release(turn.turnId());throw ex;}
    }
    private TurnState requireOwner(WebSocketSession session,String device,String turnId){TurnState s=states.get(turnId);if(s==null)throw new ProtocolError("RESUME_EXPIRED",true);if(deviceSockets.get(device)!=session||!turns.ownsTurn(device,turnId,s.ownerVersion())||!turnId.equals(sessionTurns.get(session.getId()))||owner(session,turnId)!=s.ownerVersion())throw new ProtocolError("STALE_OWNER",false);return s;}
    private long owner(WebSocketSession s,String id){Long v=sessionOwners.get(s.getId());if(v==null||!id.equals(sessionTurns.get(s.getId())))throw new ProtocolError("TURN_REQUIRED",false);return v;}
    private void bind(WebSocketSession session,String device,String turn,long owner){WebSocketSession previous=deviceSockets.put(device,session);if(previous!=null&&previous!=session&&previous.isOpen())try{previous.close(CloseStatus.NORMAL.withReason("connection replaced"));}catch(Exception ignored){}sessionTurns.put(session.getId(),turn);sessionOwners.put(session.getId(),owner);}
    private void reserveState(VoiceTurnService.Turn turn){if(activeTurns.incrementAndGet()>maxTurns){activeTurns.decrementAndGet();throw new ProtocolError("VOICE_CAPACITY_EXCEEDED",true);}}
    private Object lockFor(String device){return deviceLocks[(device.hashCode()&0x7fffffff)%deviceLocks.length];}
    private void release(String turn){if(states.remove(turn)!=null)activeTurns.decrementAndGet();formats.remove(turn);turnDevices.remove(turn);deadlines.remove(turn);disconnectTimes.remove(turn);}
    private void sendAck(WebSocketSession s,String id,TurnState state)throws Exception{send(s,Map.of("version",1,"type","ack","turnId",id,"receivedThrough",state.receivedThrough(),"missingRanges",state.missingRanges()));}
    private void sendError(WebSocketSession s,String code,boolean retryable){try{send(s,Map.of("version",1,"type","error","code",code,"retryable",retryable,"traceId",UUID.randomUUID().toString()));}catch(Exception ignored){}}
    private void send(WebSocketSession s,Object event)throws Exception{if(s.isOpen())s.sendMessage(new TextMessage(mapper.writeValueAsString(event)));}
    @Override protected void handleBinaryMessage(WebSocketSession session,BinaryMessage message)throws Exception{try{binary(session,message);}catch(ProtocolError ex){if("FORMAT_MISMATCH".equals(ex.code))failCurrent(session,(String)session.getAttributes().get("deviceId"));sendError(session,ex.code,ex.retryable);}catch(AudioFrameCodec.FrameException ex){VoiceTurnCoordinator.State state=coordinator.state((String)session.getAttributes().get("deviceId"));if(state==VoiceTurnCoordinator.State.LISTENING||state==VoiceTurnCoordinator.State.SPEAKING)failCurrent(session,(String)session.getAttributes().get("deviceId"));sendError(session,ex.code(),false);}catch(RuntimeException ex){VoiceTurnCoordinator.State state=coordinator.state((String)session.getAttributes().get("deviceId"));if(state==VoiceTurnCoordinator.State.LISTENING||state==VoiceTurnCoordinator.State.SPEAKING)failCurrent(session,(String)session.getAttributes().get("deviceId"));sendError(session,"INVALID_AUDIO_EVENT",false);}}
    @Override public void afterConnectionEstablished(WebSocketSession session){}
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){String turn=sessionTurns.remove(session.getId());Long owner=sessionOwners.remove(session.getId());String device=(String)session.getAttributes().get("deviceId");if(device!=null&&turn!=null&&owner!=null)try{if(turns.markDisconnected(device,turn,owner))disconnectTimes.put(turn,Instant.now());}catch(RuntimeException ignored){}if(device!=null)deviceSockets.remove(device,session);}
    @Scheduled(fixedDelayString="${app.voice.cleanup-interval-ms:5000}") public void cleanupExpired(){Instant now=Instant.now();deadlines.forEach((id,deadline)->{TurnState s=states.get(id);String device=turnDevices.get(id);if(s==null||device==null)return;try{
        VoiceTurnCoordinator.State voiceState=coordinator.state(device);
        if(voiceState==VoiceTurnCoordinator.State.LISTENING||voiceState==VoiceTurnCoordinator.State.SPEAKING){long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-s.startedNanos());voiceState=coordinator.timeout(device,elapsed);}
        WebSocketSession socket=deviceSockets.get(device);
        if(voiceState==VoiceTurnCoordinator.State.FAILED){String code=coordinator.failureCode(device);turns.fail(device,id,s.ownerVersion());release(id);if(socket!=null)sendError(socket,code,false);return;}
        if(voiceState==VoiceTurnCoordinator.State.DIALOGUE_PENDING){if(!turns.ownsTurn(device,id,s.ownerVersion()))return;turns.persistFinal(device,id,s.ownerVersion(),coordinator.finalText(device,id));voiceState=coordinator.deliverDialogue(device,id);if(voiceState==VoiceTurnCoordinator.State.FAILED){turns.failFinal(device,id,s.ownerVersion());release(id);if(socket!=null)sendError(socket,coordinator.failureCode(device),false);return;}}
        boolean firstDelivery=voiceState==VoiceTurnCoordinator.State.PLAYING&&s.claimDelivery();
        if(firstDelivery){synchronized(s){if(!turns.ownsTurn(device,id,s.ownerVersion()))return;String text=coordinator.finalText(device,id);if(text==null)return;var command=coordinator.command(device,id);if(command!=null&&socket!=null&&socket.isOpen())send(socket,Map.of("version",1,"type","final","turnId",id,"text",text,"finalVersion",1,"asrAttemptId",s.attemptId().toString(),"command",command));}}
        if(voiceState==VoiceTurnCoordinator.State.PLAYING){if(coordinator.commandExpired(device)){coordinator.cancel(device,id,s.attemptId().toString());turns.acknowledgeFinal(device,id,s.ownerVersion());release(id);}return;}
        Instant disconnected=disconnectTimes.get(id);if(now.isAfter(deadline.plusSeconds(10))||(disconnected!=null&&now.isAfter(disconnected.plusSeconds(10)))){turns.fail(device,id,s.ownerVersion());release(id);}
    }catch(Exception ignored){}});}
    private void failCurrent(WebSocketSession session,String device){if(device==null)return;String id=sessionTurns.get(session.getId());TurnState s=id==null?null:states.get(id);Long owner=sessionOwners.get(session.getId());if(s!=null&&owner!=null&&owner==s.ownerVersion()&&deviceSockets.get(device)==session){try{turns.fail(device,id,owner);release(id);}catch(RuntimeException ignored){}}}
    @PreDestroy public void close(){timer.shutdownNow();}
    private static final class ProtocolError extends RuntimeException{final String code;final boolean retryable;ProtocolError(String c,boolean r){code=c;retryable=r;}}
}
