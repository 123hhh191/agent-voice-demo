package com.example.agentvoice.voice;

import com.example.agentvoice.common.ApiException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log=LoggerFactory.getLogger(DeviceGateway.class);
    private final ObjectMapper mapper;private final VoiceTurnService turns;private final ResumeService resumeService;private final DeviceAuthService auth;private final VoiceTurnCoordinator coordinator;private final VoicePlaybackService playback;private final BoardAdapterRegistry boardAdapters;private final CommandDispatcher commands;private final String boardModel;
    private final ConcurrentHashMap<String,TurnState> states=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,WebSocketSession> deviceSockets=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,String> sessionTurns=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Long> sessionOwners=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,VoiceTurnService.AudioFormat> formats=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,String> turnDevices=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Instant> deadlines=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Instant> disconnectTimes=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,FinalizationJob> finalizations=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,java.util.List<java.util.concurrent.ScheduledFuture<?>>> recordingTimers=new ConcurrentHashMap<>();
    private final com.example.agentvoice.task.TaskWorker worker;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"voice-gap-timeout");t.setDaemon(true);return t;});
    private final AtomicInteger activeTurns=new AtomicInteger();private final int maxTurns,maxAudioBytes,reorderLimit;private final long gapWaitMs;
    private final Object[] deviceLocks=new Object[64];

    public DeviceGateway(ObjectMapper mapper,VoiceTurnService turns,ResumeService resumeService,DeviceAuthService auth,VoiceTurnCoordinator coordinator,VoicePlaybackService playback,BoardAdapterRegistry boardAdapters,CommandDispatcher commands,com.example.agentvoice.task.TaskWorker worker,
            @Value("${app.voice.max-active-turns:64}") int maxTurns,@Value("${app.voice.max-audio-bytes:960000}") int maxAudioBytes,
            @Value("${app.voice.reorder-limit:64}") int reorderLimit,@Value("${app.voice.gap-wait-ms:1000}") long gapWaitMs,@Value("${app.voice.board-model:MODEL_A}") String boardModel){
        this.mapper=mapper;this.turns=turns;this.resumeService=resumeService;this.auth=auth;this.coordinator=coordinator;this.playback=playback;this.boardAdapters=boardAdapters;this.commands=commands;this.boardModel=boardModel;this.maxTurns=maxTurns;this.maxAudioBytes=maxAudioBytes;this.reorderLimit=reorderLimit;this.gapWaitMs=gapWaitMs;
        this.worker=worker;
        if(maxTurns<1||maxAudioBytes<640||reorderLimit<1||gapWaitMs<1)throw new IllegalArgumentException("voice limits must be positive and fit at least one PCM frame");
        java.util.Arrays.setAll(deviceLocks,i->new Object());
    }

    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message)throws Exception{
        String device=(String)session.getAttributes().get("deviceId");
        String turnId=sessionTurns.get(session.getId());
        try{
            if(!device.equals(auth.authenticate((String)session.getAttributes().get("deviceToken"))))throw new ProtocolError("DEVICE_AUTH_FAILED",false);
            JsonNode root=mapper.readTree(message.getPayload());if(root.path("version").asInt()!=1)throw new ProtocolError("UNSUPPORTED_VERSION",false);
            String type=root.path("type").asText("");JsonNode payload=root.path("payload");
            String eventTurnId=payload.path("turnId").asText("");if(!eventTurnId.isBlank())turnId=eventTurnId;
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
        catch(ApiException ex){sendError(session,ex.code(),isRetryable(ex));if("DEVICE_AUTH_FAILED".equals(ex.code()))session.close(CloseStatus.POLICY_VIOLATION);}
        catch(Exception ex){String traceId=TraceContext.current();if(traceId==null||traceId.isBlank())traceId=UUID.randomUUID().toString();log.error("Voice WebSocket operation failed traceId={} deviceId={} turnId={}",traceId,device,turnId,ex);sendError(session,"VOICE_OPERATION_FAILED",false,traceId);}
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
        scheduleRecording(device,state);
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
        scheduleRecording(device,state);
        var resumedEvent=new java.util.LinkedHashMap<String,Object>();resumedEvent.put("version",1);resumedEvent.put("type","resumed");resumedEvent.put("turnId",turnId);resumedEvent.put("asrAttemptId",resumed.attemptId());resumedEvent.put("ownerVersion",resumed.ownerVersion());resumedEvent.put("receivedThrough",state.receivedThrough());resumedEvent.put("missingRanges",state.missingRanges());resumedEvent.put("resumeStrategy",resumed.resumeStrategy());resumedEvent.put("deadlineAt",resumed.deadlineAt());var pendingCommand=commands.findPending(device,turnId);if(pendingCommand!=null&&commands.mayDispatch(pendingCommand.commandId()))resumedEvent.put("command",pendingCommand);send(session,resumedEvent);
    }

    private void end(WebSocketSession session,String device,JsonNode payload)throws Exception{synchronized(lockFor(device)){endLocked(session,device,payload);}}
    private void endLocked(WebSocketSession session,String device,JsonNode payload)throws Exception{
        String turnId=payload.path("turnId").asText("");TurnState state=requireOwner(session,device,turnId);
        var phase=coordinator.state(device);if(phase!=VoiceTurnCoordinator.State.LISTENING&&phase!=VoiceTurnCoordinator.State.SPEAKING){sendAck(session,turnId,state);return;}
        JsonNode lastNode=payload.path("lastSeq");if(!lastNode.isIntegralNumber())throw new ProtocolError("INVALID_SEQUENCE",false);long last=lastNode.asLong(-1);state.end(last);
        sendAck(session,turnId,state);
        long ownerVersion=state.ownerVersion();
        if(state.claimFinalize())finish(session,device,state);else timer.schedule(()->{
            try{synchronized(lockFor(device)){if(states.get(turnId)==state&&state.ownerVersion()==ownerVersion&&!state.endComplete()&&!finalizations.containsKey(turnId))failTurn(device,state,"MISSING_FRAMES");}}
            catch(Exception ex){log.error("Voice frame gap cleanup failed deviceId={} turnId={}",device,turnId,ex);}
        },gapWaitMs,TimeUnit.MILLISECONDS);
    }

    private void binary(WebSocketSession session,BinaryMessage message)throws Exception{synchronized(lockFor((String)session.getAttributes().get("deviceId"))){binaryLocked(session,message);}}
    private void binaryLocked(WebSocketSession session,BinaryMessage message)throws Exception{
        String turnId=sessionTurns.get(session.getId()),device=(String)session.getAttributes().get("deviceId");if(turnId==null)throw new ProtocolError("TURN_REQUIRED",false);
        TurnState state=requireOwner(session,device,turnId);
        if(state.elapsedMs()>=30_000){advance(device,state);throw new ProtocolError("NOT_LISTENING",false);}
        var current=coordinator.state(device);if(current!=VoiceTurnCoordinator.State.LISTENING&&current!=VoiceTurnCoordinator.State.SPEAKING)throw new ProtocolError("NOT_LISTENING",false);
        if(!turns.ownsRecording(device,turnId,state.attemptId().toString(),state.ownerVersion()))throw new ProtocolError("STALE_OWNER",false);
        ByteBuffer source=message.getPayload().slice();byte[] bytes=new byte[source.remaining()];source.get(bytes);
        var mapped=boardAdapters.require(boardModel).decodeAudio(device,bytes);
        AudioFrameCodec.Frame frame=new AudioFrameCodec.Frame(UUID.fromString(mapped.turnId()),UUID.fromString(mapped.attemptId()),mapped.seq(),mapped.captureOffsetMs(),mapped.durationMs(),1,mapped.format().sampleRate(),mapped.format().channels(),mapped.last(),mapped.payload());VoiceTurnService.AudioFormat f=formats.get(turnId);
        if(!turnId.equals(mapped.turnId())||f==null||frame.codec()!=f.codec()||frame.sampleRate()!=f.sampleRate()||frame.channels()!=f.channels()||frame.durationMs()!=f.frameDurationMs())throw new ProtocolError("FORMAT_MISMATCH",false);
        var result=state.accept(frame);for(var contiguous:result.contiguous()){coordinator.accept(new com.example.agentvoice.audio.AudioEvent(device,turnId,frame.attemptId().toString(),com.example.agentvoice.audio.AudioEvent.Type.FRAME,contiguous.seq(),contiguous.captureOffsetMs(),contiguous.durationMs(),com.example.agentvoice.audio.AudioFormat.PCM16_MONO_16K,contiguous.payload(),contiguous.last()),state.elapsedMs());if(coordinator.state(device)==VoiceTurnCoordinator.State.FAILED){failTurn(device,state,coordinator.failureCode(device));return;}}send(session,Map.of("version",1,"type","ack","turnId",turnId,"receivedThrough",result.receivedThrough(),"missingRanges",result.missingRanges()));
        advance(device,state);
        if(state.endComplete()&&state.claimFinalize())finish(session,device,state);
    }

    private void finish(WebSocketSession session,String device,TurnState state)throws Exception{
        String turnId=state.turnId().toString();if(!turns.ownsTurn(device,turnId,state.ownerVersion()))throw new ProtocolError("STALE_OWNER",false);
        coordinator.end(device,turnId,state.attemptId().toString());
        advance(device,state);
    }
    /** Timer threads only claim/schedule transitions; blocking finishInput runs on TaskWorker. */
    private void advance(String device,TurnState state){synchronized(lockFor(device)){
        String id=state.turnId().toString();if(states.get(id)!=state)return;
        try{
            long elapsed=state.elapsedMs();Instant recordingDeadline=deadlines.get(id);
            if(recordingDeadline!=null&&!Instant.now().isBefore(recordingDeadline))elapsed=Math.max(elapsed,30_000);
            var phase=coordinator.timeout(device,elapsed);
            if(phase==VoiceTurnCoordinator.State.FAILED){failTurn(device,state,coordinator.failureCode(device));return;}
            if(phase!=VoiceTurnCoordinator.State.FINALIZING||finalizations.containsKey(id))return;
            long owner=state.ownerVersion();String attempt=state.attemptId().toString();
            if(!turns.beginFinalization(device,id,attempt,owner))return;
            state.stopInput();cancelRecordingTimers(id);
            var job=new FinalizationJob(owner,Instant.now().plusMillis(turns.finalizationTimeoutMs()),traceId());finalizations.put(id,job);
            log.info("ASR finalization started traceId={} deviceId={} turnId={} attemptId={} ownerVersion={} cause={} deadlineType=PROCESSING",job.traceId,device,id,attempt,owner,coordinator.endpointCause(device));
            job.timeout=timer.schedule(()->{TraceContext.begin(job.traceId);try{synchronized(lockFor(device)){if(finalizations.get(id)==job)failTurn(device,state,"ASR_FINAL_TIMEOUT");}}finally{TraceContext.clear();}},turns.finalizationTimeoutMs(),TimeUnit.MILLISECONDS);
            try{job.work=worker.submitCancellable(()->{
                job.runningThread=Thread.currentThread();
                String priorTrace=TraceContext.current();TraceContext.begin(job.traceId);
                try{
                    if(!turns.ownsProcessing(device,id,attempt,owner))return;
                    coordinator.finishInput(device,id,attempt);
                    if(states.get(id)!=state||state.ownerVersion()!=owner)return;
                    deliverFinal(deviceSockets.get(device),device,state);
                }catch(ApiException ex){log.warn("ASR final result rejected deviceId={} turnId={} code={}",device,id,ex.code());}
                catch(Exception ex){log.error("ASR finalization worker failed traceId={} deviceId={} turnId={}",job.traceId,device,id,ex);if(states.get(id)==state)failTurn(device,state,"ASR_FAILED");}
                finally{if(priorTrace==null)TraceContext.clear();else TraceContext.begin(priorTrace);}
            });}catch(java.util.concurrent.RejectedExecutionException ex){failTurn(device,state,"ASR_BUSY");}
        }catch(ApiException ex){log.warn("Voice finalization ownership rejected deviceId={} turnId={} code={}",device,id,ex.code());}
        catch(RuntimeException ex){log.error("Voice finalization scheduling failed deviceId={} turnId={}",device,id,ex);failTurn(device,state,"VOICE_OPERATION_FAILED");}
    }}
    private void scheduleRecording(String device,TurnState state){
        String id=state.turnId().toString();recordingTimers.computeIfAbsent(id,key->java.util.List.of(
                timer.schedule(()->advance(device,state),Math.max(0,5_000-state.elapsedMs()),TimeUnit.MILLISECONDS),
                timer.schedule(()->advance(device,state),Math.max(0,java.time.Duration.between(Instant.now(),deadlines.get(id)).toMillis())+1,TimeUnit.MILLISECONDS)));
    }
    private void cancelRecordingTimers(String id){var jobs=recordingTimers.remove(id);if(jobs!=null)jobs.forEach(f->f.cancel(false));}
    private void failTurn(String device,TurnState state,String code){synchronized(lockFor(device)){
        String id=state.turnId().toString();if(states.get(id)!=state)return;
        turns.fail(device,id,state.ownerVersion());coordinator.discard(device,id,state.attemptId().toString());release(id);
        String trace=traceId();log.warn("Voice turn failed traceId={} deviceId={} turnId={} code={} deadlineType={}",trace,device,id,code,"ASR_FINAL_TIMEOUT".equals(code)?"PROCESSING":"NONE");
        WebSocketSession socket=deviceSockets.get(device);if(socket!=null)sendError(socket,code,"ASR_BUSY".equals(code)||"MISSING_FRAMES".equals(code),trace);
    }}
    private void deliverFinal(WebSocketSession session,String device,TurnState state)throws Exception{
        String turnId=state.turnId().toString();
        if(coordinator.state(device)==VoiceTurnCoordinator.State.FAILED){failTurn(device,state,coordinator.failureCode(device));return;}
        if(coordinator.state(device)==VoiceTurnCoordinator.State.DIALOGUE_PENDING){
            synchronized(lockFor(device)){
                if(states.get(turnId)!=state)return;
                turns.persistFinal(device,turnId,state.attemptId().toString(),state.ownerVersion(),coordinator.finalText(device,turnId));
                var job=finalizations.remove(turnId);if(job!=null&&job.timeout!=null)job.timeout.cancel(false);
            }
            coordinator.deliverDialogue(device,turnId);if(coordinator.state(device)==VoiceTurnCoordinator.State.FAILED){String code=coordinator.failureCode(device);turns.failFinal(device,turnId,state.ownerVersion());coordinator.discard(device,turnId,state.attemptId().toString());release(turnId);if(session!=null)sendError(session,code,false);return;}}
        if(coordinator.state(device)!=VoiceTurnCoordinator.State.PLAYING||!state.claimDelivery())return;
        if(!turns.ownsTurn(device,turnId,state.ownerVersion()))throw new ProtocolError("STALE_OWNER",false);
        String text=coordinator.finalText(device,turnId);if(text==null)throw new ProtocolError("ASR_FINAL_MISSING",false);
        var command=coordinator.command(device,turnId);if(command==null)throw new ProtocolError("AUDIO_OUTPUT_FAILED",false);
        if(session!=null&&session.isOpen())send(session,Map.of("version",1,"type","final","turnId",turnId,"text",text,"finalVersion",1,"asrAttemptId",state.attemptId().toString(),"command",command));
    }

    private void cancel(WebSocketSession session,String device,JsonNode payload)throws Exception{synchronized(lockFor(device)){String id=payload.path("turnId").asText("");TurnState state=requireOwner(session,device,id);turns.cancel(device,id,state.ownerVersion());var stop=coordinator.cancel(device,id,state.attemptId().toString());release(id);var response=new java.util.LinkedHashMap<String,Object>();response.put("version",1);response.put("type","cancelled");response.put("turnId",id);if(stop!=null)response.put("command",stop);send(session,response);}}
    private void acknowledgeFinal(WebSocketSession session,String device,JsonNode payload)throws Exception{String id=payload.path("turnId").asText("");long owner=owner(session,id);if(deviceSockets.get(device)!=session)throw new ProtocolError("STALE_OWNER",false);if(coordinator.state(device)==VoiceTurnCoordinator.State.PLAYING||commands.hasPendingPlayback(device,id))throw new ProtocolError("PLAYBACK_PENDING",false);turns.acknowledgeFinal(device,id,owner);release(id);send(session,Map.of("version",1,"type","acknowledged","turnId",id));}
    private void acknowledgeCommand(WebSocketSession session,String device,JsonNode payload)throws Exception{String id=payload.path("turnId").asText(""),commandId=payload.path("commandId").asText("");long owner=owner(session,id);if(deviceSockets.get(device)!=session)throw new ProtocolError("STALE_OWNER",false);if(coordinator.state(device)==VoiceTurnCoordinator.State.PLAYING)coordinator.commandAck(device,id,commandId);else if(!commands.acknowledge(device,id,commandId))throw new ProtocolError("COMMAND_EXPIRED",false);send(session,Map.of("version",1,"type","command_acknowledged","turnId",id,"commandId",commandId,"ownerVersion",owner));}
    private void playbackFinished(WebSocketSession session,String device,JsonNode payload)throws Exception{
        synchronized(lockFor(device)){
            String id=payload.path("turnId").asText(""),commandId=payload.path("commandId").asText("");
            long ownerVersion=owner(session,id);
            if(deviceSockets.get(device)!=session)throw new ProtocolError("STALE_OWNER",false);
            VoicePlaybackService.Completion completion=playback.complete(device,id,ownerVersion,commandId);
            if(completion.result()==VoicePlaybackService.Result.REJECTED)throw new ProtocolError(completion.errorCode(),false);

            commands.playbackCommitted(commandId);
            VoiceTurnCoordinator.PlaybackResult memoryResult=coordinator.playbackFinished(device,id,commandId);
            if(memoryResult==VoiceTurnCoordinator.PlaybackResult.REJECTED)log.warn("Committed playback receipt did not match local turn traceId={} deviceId={} turnId={}",TraceContext.current(),device,id);
            release(id);
            if(completion.result()==VoicePlaybackService.Result.APPLIED&&coordinator.reopenPolicy()==VoiceTurnCoordinator.ReopenPolicy.AUTO_LISTEN){startAutoListen(session,device,id);}
            else send(session,Map.of("version",1,"type","playback_acknowledged","turnId",id,"commandId",commandId));
        }
    }
    private void startAutoListen(WebSocketSession session,String device,String previousTurn)throws Exception{
        var format=new VoiceTurnService.AudioFormat(1,16000,1,20);var turn=turns.start(device,"auto-"+previousTurn,format);
        try{reserveState(turn);}catch(RuntimeException ex){turns.cancel(device,turn.turnId(),turn.ownerVersion());throw ex;}
        var state=new TurnState(UUID.fromString(turn.turnId()),UUID.fromString(turn.attemptId()),turn.ownerVersion(),maxAudioBytes,reorderLimit,System.nanoTime());
        states.put(turn.turnId(),state);formats.put(turn.turnId(),format);turnDevices.put(turn.turnId(),device);deadlines.put(turn.turnId(),Instant.parse(turn.deadlineAt()));coordinator.restart(device,turn.turnId(),turn.attemptId(),"模拟识别输入");bind(session,device,turn.turnId(),turn.ownerVersion());
        scheduleRecording(device,state);
        try{var command=new DeviceCommand(UUID.randomUUID().toString(),device,turn.turnId(),DeviceCommand.Type.START_LISTENING,1,Instant.parse(turn.deadlineAt()),Map.of("audioFormat",format,"resumePolicy","AUTO_LISTEN"));commands.persist(command);
            send(session,Map.of("version",1,"type","listening_started","turnId",turn.turnId(),"asrAttemptId",turn.attemptId(),"resumeToken",turn.resumeToken(),"ownerVersion",turn.ownerVersion(),"deadlineAt",turn.deadlineAt(),"command",command));
        }catch(Exception ex){turns.cancel(device,turn.turnId(),turn.ownerVersion());coordinator.cancel(device,turn.turnId(),turn.attemptId());release(turn.turnId());throw ex;}
    }
    private TurnState requireOwner(WebSocketSession session,String device,String turnId){TurnState s=states.get(turnId);if(s==null)throw new ProtocolError("RESUME_EXPIRED",false);if(deviceSockets.get(device)!=session||!turns.ownsTurn(device,turnId,s.ownerVersion())||!turnId.equals(sessionTurns.get(session.getId()))||owner(session,turnId)!=s.ownerVersion())throw new ProtocolError("STALE_OWNER",false);return s;}
    private long owner(WebSocketSession s,String id){Long v=sessionOwners.get(s.getId());if(v==null||!id.equals(sessionTurns.get(s.getId())))throw new ProtocolError("TURN_REQUIRED",false);return v;}
    private void bind(WebSocketSession session,String device,String turn,long owner){WebSocketSession previous=deviceSockets.put(device,session);if(previous!=null&&previous!=session&&previous.isOpen())try{previous.close(CloseStatus.NORMAL.withReason("connection replaced"));}catch(Exception ignored){}sessionTurns.put(session.getId(),turn);sessionOwners.put(session.getId(),owner);}
    private void reserveState(VoiceTurnService.Turn turn){if(activeTurns.incrementAndGet()>maxTurns){activeTurns.decrementAndGet();throw new ProtocolError("VOICE_CAPACITY_EXCEEDED",true);}}
    private Object lockFor(String device){return deviceLocks[(device.hashCode()&0x7fffffff)%deviceLocks.length];}
    private void release(String turn){if(states.remove(turn)!=null)activeTurns.decrementAndGet();formats.remove(turn);turnDevices.remove(turn);deadlines.remove(turn);disconnectTimes.remove(turn);cancelRecordingTimers(turn);var job=finalizations.remove(turn);if(job!=null){if(job.timeout!=null)job.timeout.cancel(false);if(job.work!=null&&job.runningThread!=Thread.currentThread())job.work.cancel(true);}}
    private void sendAck(WebSocketSession s,String id,TurnState state)throws Exception{send(s,Map.of("version",1,"type","ack","turnId",id,"receivedThrough",state.receivedThrough(),"missingRanges",state.missingRanges()));}
    private boolean isRetryable(ApiException ex){return ex.status()==HttpStatus.TOO_MANY_REQUESTS||ex.status()==HttpStatus.SERVICE_UNAVAILABLE||ex.status()==HttpStatus.GATEWAY_TIMEOUT;}
    private String traceId(){String current=TraceContext.current();return current==null?UUID.randomUUID().toString():current;}
    private void sendError(WebSocketSession s,String code,boolean retryable){sendError(s,code,retryable,UUID.randomUUID().toString());}
    private void sendError(WebSocketSession s,String code,boolean retryable,String traceId){try{send(s,Map.of("version",1,"type","error","code",code,"retryable",retryable,"traceId",traceId));}catch(Exception ex){log.warn("Voice error delivery failed traceId={} code={}",traceId,code,ex);}}
    private void send(WebSocketSession s,Object event)throws Exception{synchronized(s){if(s.isOpen())s.sendMessage(new TextMessage(mapper.writeValueAsString(event)));}}
    @Override protected void handleBinaryMessage(WebSocketSession session,BinaryMessage message)throws Exception{try{binary(session,message);}catch(ProtocolError ex){if("FORMAT_MISMATCH".equals(ex.code))failCurrent(session,(String)session.getAttributes().get("deviceId"));sendError(session,ex.code,ex.retryable);}catch(AudioFrameCodec.FrameException ex){VoiceTurnCoordinator.State state=coordinator.state((String)session.getAttributes().get("deviceId"));if(state==VoiceTurnCoordinator.State.LISTENING||state==VoiceTurnCoordinator.State.SPEAKING)failCurrent(session,(String)session.getAttributes().get("deviceId"));sendError(session,ex.code(),false);}catch(RuntimeException ex){VoiceTurnCoordinator.State state=coordinator.state((String)session.getAttributes().get("deviceId"));if(state==VoiceTurnCoordinator.State.LISTENING||state==VoiceTurnCoordinator.State.SPEAKING)failCurrent(session,(String)session.getAttributes().get("deviceId"));sendError(session,"INVALID_AUDIO_EVENT",false);}}
    @Override public void afterConnectionEstablished(WebSocketSession session){}
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){String turn=sessionTurns.remove(session.getId());Long owner=sessionOwners.remove(session.getId());String device=(String)session.getAttributes().get("deviceId");if(device!=null&&turn!=null&&owner!=null)try{if(turns.markDisconnected(device,turn,owner))disconnectTimes.put(turn,Instant.now());}catch(RuntimeException ignored){}if(device!=null)deviceSockets.remove(device,session);}
    @Scheduled(fixedDelayString="${app.voice.cleanup-interval-ms:5000}") public void cleanupExpired(){Instant now=Instant.now();states.forEach((id,s)->{
        String device=turnDevices.get(id);if(device==null)return;
        synchronized(lockFor(device)){try{
            if(states.get(id)!=s)return;
            if(!turns.ownsTurn(device,id,s.ownerVersion())){coordinator.discard(device,id,s.attemptId().toString());release(id);return;}
            var job=finalizations.get(id);
            if(job!=null){if(!now.isBefore(job.deadline))failTurn(device,s,"ASR_FINAL_TIMEOUT");return;}
            var phase=coordinator.state(device);
            if(phase==VoiceTurnCoordinator.State.PLAYING){if(coordinator.commandExpired(device)){coordinator.cancel(device,id,s.attemptId().toString());turns.acknowledgeFinal(device,id,s.ownerVersion());release(id);}return;}
            // FINAL/DIALOGUE_PENDING is already committed; no recording/reconnect timeout applies.
            if(phase==VoiceTurnCoordinator.State.DIALOGUE_PENDING)return;
            Instant disconnected=disconnectTimes.get(id);
            if(disconnected!=null&&now.isAfter(disconnected.plusSeconds(10))){failTurn(device,s,"RESUME_EXPIRED");return;}
            advance(device,s);
        }catch(Exception ex){log.error("Voice cleanup failed deviceId={} turnId={}",device,id,ex);}}
    });}
    private void failCurrent(WebSocketSession session,String device){if(device==null)return;String id=sessionTurns.get(session.getId());TurnState s=id==null?null:states.get(id);Long owner=sessionOwners.get(session.getId());if(s!=null&&owner!=null&&owner==s.ownerVersion()&&deviceSockets.get(device)==session){try{failTurn(device,s,"INVALID_AUDIO_EVENT");}catch(RuntimeException ex){log.error("Voice failure cleanup failed deviceId={} turnId={}",device,id,ex);}}}
    @PreDestroy public void close(){timer.shutdownNow();finalizations.values().forEach(job->{if(job.work!=null)job.work.cancel(true);});states.forEach((id,s)->{String device=turnDevices.get(id);if(device!=null)coordinator.discard(device,id,s.attemptId().toString());});}
    private static final class FinalizationJob {
        final long owner;final Instant deadline;final String traceId;volatile Thread runningThread;volatile java.util.concurrent.Future<?> work;volatile java.util.concurrent.ScheduledFuture<?> timeout;
        FinalizationJob(long owner,Instant deadline,String traceId){this.owner=owner;this.deadline=deadline;this.traceId=traceId;}
    }
    private static final class ProtocolError extends RuntimeException{final String code;final boolean retryable;ProtocolError(String c,boolean r){code=c;retryable=r;}}
}
