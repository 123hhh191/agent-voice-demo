package com.example.agentvoice.voice;

import com.example.agentvoice.audio.AudioEvent;
import com.example.agentvoice.audio.AudioNormalizer;
import com.example.agentvoice.asr.AsrEvent;
import com.example.agentvoice.asr.AsrProviderAdapter;
import com.example.agentvoice.dialogue.DialogueClient;
import com.example.agentvoice.output.CommandDispatcher;
import com.example.agentvoice.output.DeviceCommand;
import com.example.agentvoice.tts.AudioOutputAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 每轮事件按设备串行推进；迟到 attempt、重复 finalize 和播放回执均不可重复触发副作用。 */
@Component
@org.springframework.context.annotation.Profile("!scaffold")
public final class VoiceTurnCoordinator {
    public enum State { IDLE, LISTENING, SPEAKING, FINALIZING, DIALOGUE_PENDING, PLAYING, FAILED }
    public enum ReopenPolicy { WAIT_WAKEUP, AUTO_LISTEN }
    private final AudioNormalizer normalizer=new AudioNormalizer(); private final AsrProviderAdapter asr; private final DialogueClient dialogue;
    private final AudioOutputAdapter output; private final CommandDispatcher commands; private final ReopenPolicy policy;
    private final Map<String,Turn> turns=new ConcurrentHashMap<>();
    private final Map<String,String> dialogueSessions=new ConcurrentHashMap<>();
    public VoiceTurnCoordinator(java.util.List<AsrProviderAdapter> providers,DialogueClient dialogue,AudioOutputAdapter output,CommandDispatcher commands,
                                @Value("${app.voice.asr-provider:MOCK_A}") String provider,
                                @Value("${app.voice.reopen-policy:WAIT_WAKEUP}") ReopenPolicy policy) {
        this.asr=providers.stream().filter(p->p.id().equals(provider)).findFirst().orElseThrow(()->new IllegalArgumentException("unknown ASR provider: "+provider));
        if(!asr.supportedFormats().contains(com.example.agentvoice.audio.AudioFormat.PCM16_MONO_16K))throw new IllegalArgumentException("ASR provider does not support the configured PCM16 format: "+provider);
        this.dialogue=dialogue;this.output=output;this.commands=commands;this.policy=policy;
    }
    public synchronized void start(String deviceId,String turnId,String attemptId,String fixtureText) {
        Turn prior=turns.get(deviceId); if(prior!=null&&prior.state!=State.IDLE&&prior.state!=State.FAILED)throw new IllegalStateException("device already has active voice turn");
        turns.put(deviceId,new Turn(deviceId,turnId,attemptId,fixtureText,asr.open(attemptId,fixtureText)));
    }
    public synchronized void restart(String deviceId,String turnId,String attemptId,String fixtureText) {
        Turn prior=turns.get(deviceId);if(prior!=null){prior.asr.cancel();prior.asr.close();}
        turns.put(deviceId,new Turn(deviceId,turnId,attemptId,fixtureText,asr.open(attemptId,fixtureText)));
    }
    public synchronized State accept(AudioEvent raw,long elapsedMs) {
        Turn t=require(raw.deviceId(),raw.turnId(),raw.attemptId()); if(t.state!=State.LISTENING&&t.state!=State.SPEAKING)return t.state;
        AudioEvent frame=normalizer.normalize(raw); var endpoint=t.endpoint.accept(frame,elapsedMs);
        if(t.endpoint.seenSpeech())t.state=State.SPEAKING;
        if(endpoint.isPresent()&&endpoint.get()==EndpointDetector.Cause.NO_SPEECH)finalizeTurn(t,endpoint.get());
        else {for(AsrEvent event:t.asr.submit(frame))applyAsr(t,event);if(t.asrError!=null){t.failureCode="ASR_FAILED";t.state=State.FAILED;t.asr.cancel();t.asr.close();}else endpoint.ifPresent(cause->finalizeTurn(t,cause));}
        return t.state;
    }
    public synchronized State end(String deviceId,String turnId,String attemptId) { Turn t=require(deviceId,turnId,attemptId);if(t.state!=State.LISTENING&&t.state!=State.SPEAKING)return t.state;finalizeTurn(t,t.endpoint.end().orElse(EndpointDetector.Cause.NO_SPEECH));return t.state; }
    public synchronized State timeout(String deviceId,long elapsedMs) { Turn t=turns.get(deviceId);if(t==null||(t.state!=State.LISTENING&&t.state!=State.SPEAKING))return t==null?State.IDLE:t.state;t.endpoint.timeout(elapsedMs).ifPresent(cause->finalizeTurn(t,cause));return t.state; }
    /** Called only after the caller commits the final ASR text to voice_turn. */
    public synchronized State deliverDialogue(String deviceId,String turnId){Turn t=turns.get(deviceId);if(t==null||!t.turnId.equals(turnId)||t.state!=State.DIALOGUE_PENDING)return t==null?State.IDLE:t.state;if(t.dialogueStarted)return t.state;t.dialogueStarted=true;
        try{DialogueClient.DialogueReply reply=dialogue.submit(t.deviceId,dialogueSessions.get(t.deviceId),t.turnId,t.finalText);dialogueSessions.put(t.deviceId,reply.sessionId());var asset=output.synthesize(reply.text());t.commandId=UUID.randomUUID().toString();t.command=new DeviceCommand(t.commandId,t.deviceId,t.turnId,DeviceCommand.Type.PLAY_AUDIO,1,Instant.now().plusSeconds(30),Map.of("audioFormat",asset.format(),"audioStreamId",asset.streamId(),"audioBase64",java.util.Base64.getEncoder().encodeToString(asset.bytes()),"fixture",true));commands.persist(t.command);t.state=State.PLAYING;}
        catch(RuntimeException ex){t.failureCode="DIALOGUE_OR_OUTPUT_FAILED";t.state=State.FAILED;}return t.state;
    }
    public synchronized DeviceCommand cancel(String deviceId,String turnId,String attemptId) { Turn t=require(deviceId,turnId,attemptId);DeviceCommand stop=null;if(t.state==State.PLAYING){stop=new DeviceCommand(UUID.randomUUID().toString(),deviceId,turnId,DeviceCommand.Type.STOP_PLAYBACK,2,Instant.now().plusSeconds(10),Map.of("reason","turn_cancelled"));commands.persist(stop);}t.asr.cancel();t.asr.close();t.state=State.IDLE;return stop; }
    public synchronized void commandAck(String deviceId,String turnId,String commandId) { Turn t=turns.get(deviceId);if(t==null||!t.turnId.equals(turnId)||t.state!=State.PLAYING||!t.commandId.equals(commandId))throw new IllegalArgumentException("stale command ACK");if(!commands.acknowledge(deviceId,turnId,commandId))throw new IllegalArgumentException("command expired or unknown"); }
    public synchronized void playbackFinished(String deviceId,String turnId,String commandId) { Turn t=turns.get(deviceId);if(t==null||!t.turnId.equals(turnId)||t.state!=State.PLAYING||!t.commandId.equals(commandId))return;commands.playbackFinished(deviceId,turnId,commandId);t.state=State.IDLE; }
    public synchronized State state(String deviceId){Turn t=turns.get(deviceId);return t==null?State.IDLE:t.state;}
    public ReopenPolicy reopenPolicy(){return policy;}
    public synchronized String finalText(String deviceId,String turnId){Turn t=turns.get(deviceId);return t!=null&&t.turnId.equals(turnId)?t.finalText:null;}
    public synchronized DeviceCommand command(String deviceId,String turnId){Turn t=turns.get(deviceId);if(t==null||!t.turnId.equals(turnId)||t.command==null||!commands.mayDispatch(t.commandId))return null;return t.command;}
    public synchronized String failureCode(String deviceId){Turn t=turns.get(deviceId);return t==null||t.failureCode==null?"VOICE_TURN_FAILED":t.failureCode;}
    public synchronized boolean commandExpired(String deviceId){Turn t=turns.get(deviceId);return t==null||t.command==null||!t.command.deadline().isAfter(Instant.now());}
    private void finalizeTurn(Turn t,EndpointDetector.Cause cause) {
        if(t.finalized)return;t.finalized=true;
        if(cause==EndpointDetector.Cause.NO_SPEECH){t.asr.cancel();t.asr.close();t.failureCode="NO_SPEECH";t.state=State.FAILED;return;}
        t.state=State.FINALIZING;
        try {
            for(AsrEvent e:t.asr.finishInput())applyAsr(t,e);
            if(t.asrError!=null)throw new IllegalStateException("ASR failed: "+t.asrError);
            if(t.finalText==null||t.finalText.isBlank())throw new IllegalStateException("ASR returned no final text");
            t.state=State.DIALOGUE_PENDING;
        } catch(RuntimeException ex){t.failureCode=t.asrError!=null?"ASR_FAILED":(t.state==State.DIALOGUE_PENDING?"DIALOGUE_OR_OUTPUT_FAILED":"VOICE_TURN_FAILED");t.state=State.FAILED;t.asr.cancel();}
        finally {t.asr.close();}
    }
    private void applyAsr(Turn t,AsrEvent e) {
        if(!t.attemptId.equals(e.attemptId()))return;
        if(e.type()==AsrEvent.Type.ERROR){t.asrError=e.errorCode()==null?"ASR_ERROR":e.errorCode();return;}
        if(e.type()==AsrEvent.Type.PARTIAL){Long v=t.revisions.get(e.segmentId());if(v==null||e.revision()>v){t.revisions.put(e.segmentId(),e.revision());t.partials.put(e.segmentId(),e.text());}}
        if(e.type()==AsrEvent.Type.FINAL){Long v=t.revisions.get(e.segmentId());if(v==null||e.revision()>=v){t.revisions.put(e.segmentId(),e.revision());t.finals.put(e.segmentId(),e.text());t.partials.remove(e.segmentId());t.finalText=t.finals.values().stream().reduce((a,b)->a+" "+b).orElse(null);}}
    }
    private Turn require(String device,String id,String attempt){Turn t=turns.get(device);if(t==null||!t.turnId.equals(id)||!t.attemptId.equals(attempt))throw new IllegalStateException("stale or unknown voice attempt");return t;}
    private static final class Turn {
        final String deviceId,turnId,attemptId,fixture;final com.example.agentvoice.asr.AsrSession asr;final EndpointDetector endpoint=new EnergyVad(600,30_000,5_000,0.015);
        final Map<String,Long> revisions=new java.util.HashMap<>();final Map<String,String> partials=new java.util.HashMap<>(),finals=new java.util.TreeMap<>();
        State state=State.LISTENING;String finalText,commandId,asrError,failureCode;DeviceCommand command;boolean finalized,dialogueStarted;
        Turn(String d,String t,String a,String f,com.example.agentvoice.asr.AsrSession s){deviceId=d;turnId=t;attemptId=a;fixture=f;asr=s;}
    }
}
