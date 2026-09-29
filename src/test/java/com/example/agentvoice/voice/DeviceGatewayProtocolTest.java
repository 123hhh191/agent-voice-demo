package com.example.agentvoice.voice;

import com.example.agentvoice.common.ApiException;
import com.example.agentvoice.device.DeviceAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeviceGatewayProtocolTest {
    @Test void preservesDeviceBusyAndDoesNotMutateExistingTurnState()throws Exception{
        var turns=mock(VoiceTurnService.class);var auth=mock(DeviceAuthService.class);var coordinator=mock(VoiceTurnCoordinator.class);var session=session("ws-busy");
        when(auth.authenticate("test-token")).thenReturn("dev-1");
        when(turns.start(eq("dev-1"),eq("req-2"),any())).thenThrow(new ApiException(HttpStatus.CONFLICT,"DEVICE_BUSY","设备已有活动轮次"));
        var gateway=gateway(turns,auth,coordinator);

        gateway.handleMessage(session,new TextMessage(startMessage("req-2")));

        var error=sentEvent(session);
        assertEquals("DEVICE_BUSY",error.path("code").asText());
        assertFalse(error.path("retryable").asBoolean());
        verify(turns,never()).cancel(anyString(),anyString(),anyLong());
        verifyNoInteractions(coordinator);
        verify(session,never()).close(any(CloseStatus.class));
    }

    @Test void authenticationFailureKeepsItsCodeAndClosesTheConnection()throws Exception{
        var turns=mock(VoiceTurnService.class);var auth=mock(DeviceAuthService.class);var coordinator=mock(VoiceTurnCoordinator.class);var session=session("ws-auth");
        when(auth.authenticate("test-token")).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED,"DEVICE_AUTH_FAILED","设备认证失败"));
        var gateway=gateway(turns,auth,coordinator);

        gateway.handleMessage(session,new TextMessage(startMessage("req-1")));

        var error=sentEvent(session);
        assertEquals("DEVICE_AUTH_FAILED",error.path("code").asText());
        assertFalse(error.path("retryable").asBoolean());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(turns,coordinator);
    }

    @Test void marksTemporaryDependencyFailuresRetryable()throws Exception{
        var turns=mock(VoiceTurnService.class);var auth=mock(DeviceAuthService.class);var coordinator=mock(VoiceTurnCoordinator.class);var session=session("ws-dependency");
        when(auth.authenticate("test-token")).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"DEVICE_AUTH_UNAVAILABLE","认证服务暂不可用"));
        var gateway=gateway(turns,auth,coordinator);

        gateway.handleMessage(session,new TextMessage(startMessage("req-1")));

        var error=sentEvent(session);
        assertEquals("DEVICE_AUTH_UNAVAILABLE",error.path("code").asText());
        assertTrue(error.path("retryable").asBoolean());
        verify(session,never()).close(any(CloseStatus.class));
    }

    @Test void rejectsInvalidRequestParametersWithoutRetry()throws Exception{
        var turns=mock(VoiceTurnService.class);var auth=mock(DeviceAuthService.class);var coordinator=mock(VoiceTurnCoordinator.class);var session=session("ws-invalid");
        when(auth.authenticate("test-token")).thenReturn("dev-1");
        var gateway=gateway(turns,auth,coordinator);

        gateway.handleMessage(session,new TextMessage(startMessage("contains spaces")));

        var error=sentEvent(session);
        assertEquals("INVALID_REQUEST",error.path("code").asText());
        assertFalse(error.path("retryable").asBoolean());
        verifyNoInteractions(turns,coordinator);
    }

    @Test void preservesInvalidParameterApiExceptionCode()throws Exception{
        var turns=mock(VoiceTurnService.class);var auth=mock(DeviceAuthService.class);var coordinator=mock(VoiceTurnCoordinator.class);var session=session("ws-format");
        when(auth.authenticate("test-token")).thenReturn("dev-1");
        when(turns.start(eq("dev-1"),eq("req-1"),any())).thenThrow(new ApiException(HttpStatus.BAD_REQUEST,"INVALID_AUDIO_FORMAT","音频格式无效"));
        var gateway=gateway(turns,auth,coordinator);

        gateway.handleMessage(session,new TextMessage(startMessage("req-1")));

        var error=sentEvent(session);
        assertEquals("INVALID_AUDIO_FORMAT",error.path("code").asText());
        assertFalse(error.path("retryable").asBoolean());
        verifyNoInteractions(coordinator);
    }

    @Test void reportsUnexpectedErrorsAsControlledNonRetryableFailures()throws Exception{
        var turns=mock(VoiceTurnService.class);var auth=mock(DeviceAuthService.class);var coordinator=mock(VoiceTurnCoordinator.class);var session=session("ws-failure");
        when(auth.authenticate("test-token")).thenReturn("dev-1");
        when(turns.start(eq("dev-1"),eq("req-1"),any())).thenThrow(new IllegalStateException("unexpected failure"));
        var gateway=gateway(turns,auth,coordinator);

        gateway.handleMessage(session,new TextMessage(startMessage("req-1")));

        var error=sentEvent(session);
        assertEquals("VOICE_OPERATION_FAILED",error.path("code").asText());
        assertFalse(error.path("retryable").asBoolean());
        assertTrue(error.hasNonNull("traceId"));
        verifyNoInteractions(coordinator);
    }

    @Test void expiredTurnIsNotMarkedRetryable()throws Exception{
        var turns=mock(VoiceTurnService.class);var auth=mock(DeviceAuthService.class);var coordinator=mock(VoiceTurnCoordinator.class);var session=session("ws-expired");
        when(auth.authenticate("test-token")).thenReturn("dev-1");
        var gateway=gateway(turns,auth,coordinator);

        gateway.handleMessage(session,new TextMessage("{\"version\":1,\"type\":\"end\",\"payload\":{\"turnId\":\"expired-turn\",\"lastSeq\":0}}"));

        var error=lastSentEvent(session);
        assertEquals("RESUME_EXPIRED",error.path("code").asText());
        assertFalse(error.path("retryable").asBoolean());
        verifyNoInteractions(turns,coordinator);
    }

    @Test void staleOwnerIsNotMarkedRetryableOrFailedAgain()throws Exception{
        var turns=mock(VoiceTurnService.class);var auth=mock(DeviceAuthService.class);var coordinator=mock(VoiceTurnCoordinator.class);var session=session("ws-stale-owner");
        when(auth.authenticate("test-token")).thenReturn("dev-1");
        UUID turnId=UUID.randomUUID(),attemptId=UUID.randomUUID();
        var started=new VoiceTurnService.Turn(turnId.toString(),attemptId.toString(),"R".repeat(43),1,Instant.now().plusSeconds(30).toString(),"RECORDING",null,"CONTINUE");
        when(turns.start(eq("dev-1"),eq("req-1"),any())).thenReturn(started);
        when(turns.ownsTurn("dev-1",turnId.toString(),1)).thenReturn(false);
        var gateway=gateway(turns,auth,coordinator);

        gateway.handleMessage(session,new TextMessage(startMessage("req-1")));
        gateway.handleMessage(session,new TextMessage("{\"version\":1,\"type\":\"end\",\"payload\":{\"turnId\":\""+turnId+"\",\"lastSeq\":0}}"));

        var error=lastSentEvent(session);
        assertEquals("STALE_OWNER",error.path("code").asText());
        assertFalse(error.path("retryable").asBoolean());
        verify(turns,never()).fail(eq("dev-1"),eq(turnId.toString()),anyLong());
    }

    @Test void rejectedPlaybackReceiptDoesNotReleaseOrAutoListen()throws Exception{
        var turns=mock(VoiceTurnService.class);var auth=mock(DeviceAuthService.class);var coordinator=mock(VoiceTurnCoordinator.class);var commands=mock(com.example.agentvoice.output.CommandDispatcher.class);var playback=mock(VoicePlaybackService.class);var session=session("ws-rejected-playback");
        when(auth.authenticate("test-token")).thenReturn("dev-1");
        UUID turnId=UUID.randomUUID(),attemptId=UUID.randomUUID();
        var started=new VoiceTurnService.Turn(turnId.toString(),attemptId.toString(),"R".repeat(43),1,Instant.now().plusSeconds(30).toString(),"RECORDING",null,"CONTINUE");
        when(turns.start(eq("dev-1"),eq("req-1"),any())).thenReturn(started);
        when(playback.complete("dev-1",turnId.toString(),1,"wrong-command")).thenReturn(new VoicePlaybackService.Completion(VoicePlaybackService.Result.REJECTED,"STALE_COMMAND"));
        when(coordinator.reopenPolicy()).thenReturn(VoiceTurnCoordinator.ReopenPolicy.AUTO_LISTEN);
        var gateway=new DeviceGateway(objectMapper(),turns,new ResumeService(turns),auth,coordinator,playback,
                new com.example.agentvoice.protocol.BoardAdapterRegistry(java.util.List.of(new com.example.agentvoice.protocol.ModelAAdapter())),commands,inlineWorker(turns,coordinator),4,960000,64,1000,"MODEL_A");

        gateway.handleMessage(session,new TextMessage(startMessage("req-1")));
        gateway.handleMessage(session,new TextMessage("{\"version\":1,\"type\":\"playback_finished\",\"payload\":{\"turnId\":\""+turnId+"\",\"commandId\":\"wrong-command\"}}"));

        var error=lastSentEvent(session);
        assertEquals("STALE_COMMAND",error.path("code").asText());
        assertFalse(error.path("retryable").asBoolean());
        verify(turns,times(1)).start(eq("dev-1"),anyString(),any());
        verify(turns,never()).acknowledgeFinal(anyString(),anyString(),anyLong());
        verify(commands,never()).playbackCommitted(anyString());
        verify(coordinator,never()).playbackFinished(anyString(),anyString(),anyString());
    }

    @Test void alreadyAppliedPlaybackReceiptAcknowledgesWithoutAutoListen()throws Exception{
        var turns=mock(VoiceTurnService.class);var auth=mock(DeviceAuthService.class);var coordinator=mock(VoiceTurnCoordinator.class);var commands=mock(com.example.agentvoice.output.CommandDispatcher.class);var playback=mock(VoicePlaybackService.class);var session=session("ws-replayed-playback");
        when(auth.authenticate("test-token")).thenReturn("dev-1");
        UUID turnId=UUID.randomUUID(),attemptId=UUID.randomUUID();
        var started=new VoiceTurnService.Turn(turnId.toString(),attemptId.toString(),"R".repeat(43),1,Instant.now().plusSeconds(30).toString(),"RECORDING",null,"CONTINUE");
        when(turns.start(eq("dev-1"),eq("req-1"),any())).thenReturn(started);
        when(playback.complete("dev-1",turnId.toString(),1,"cmd-1")).thenReturn(new VoicePlaybackService.Completion(VoicePlaybackService.Result.ALREADY_APPLIED,null));
        when(coordinator.reopenPolicy()).thenReturn(VoiceTurnCoordinator.ReopenPolicy.AUTO_LISTEN);
        var gateway=new DeviceGateway(objectMapper(),turns,new ResumeService(turns),auth,coordinator,playback,
                new com.example.agentvoice.protocol.BoardAdapterRegistry(java.util.List.of(new com.example.agentvoice.protocol.ModelAAdapter())),commands,inlineWorker(turns,coordinator),4,960000,64,1000,"MODEL_A");

        gateway.handleMessage(session,new TextMessage(startMessage("req-1")));
        gateway.handleMessage(session,new TextMessage("{\"version\":1,\"type\":\"playback_finished\",\"payload\":{\"turnId\":\""+turnId+"\",\"commandId\":\"cmd-1\"}}"));

        var response=lastSentEvent(session);
        assertEquals("playback_acknowledged",response.path("type").asText());
        verify(commands).playbackCommitted("cmd-1");
        verify(coordinator).playbackFinished("dev-1",turnId.toString(),"cmd-1");
        verify(turns,times(1)).start(eq("dev-1"),anyString(),any());
    }

    @Test void acceptsOneFrameFinalizesAndReleasesOnApplicationAck()throws Exception{
        var turns=mock(VoiceTurnService.class);var auth=mock(DeviceAuthService.class);var session=mock(WebSocketSession.class);var attrs=new HashMap<String,Object>();attrs.put("deviceId","dev-1");attrs.put("deviceToken","test-token");when(auth.authenticate("test-token")).thenReturn("dev-1");
        when(session.getAttributes()).thenReturn(attrs);when(session.getId()).thenReturn("ws-1");when(session.isOpen()).thenReturn(true);
        UUID turn=UUID.randomUUID(),attempt=UUID.randomUUID();var started=new VoiceTurnService.Turn(turn.toString(),attempt.toString(),"R".repeat(43),1,Instant.now().plusSeconds(30).toString(),"RECORDING",null,"CONTINUE");
        when(turns.start(eq("dev-1"),eq("req-1"),any())).thenReturn(started);when(turns.ownsTurn("dev-1",turn.toString(),1)).thenReturn(true);
        when(turns.persistFinal(eq("dev-1"),eq(turn.toString()),eq(attempt.toString()),eq(1L),anyString())).thenAnswer(invocation->new VoiceTurnService.Turn(turn.toString(),attempt.toString(),null,1,started.deadlineAt(),"FINAL",invocation.getArgument(4),"FINAL_SAVED"));
        var coordinator=mock(VoiceTurnCoordinator.class);var finished=new java.util.concurrent.atomic.AtomicBoolean();var finalized=new java.util.concurrent.atomic.AtomicBoolean();var delivered=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation->{finalized.set(true);return null;}).when(coordinator).end(eq("dev-1"),eq(turn.toString()),eq(attempt.toString()));
        when(coordinator.state("dev-1")).thenAnswer(invocation->!finalized.get()?VoiceTurnCoordinator.State.LISTENING:delivered.get()?VoiceTurnCoordinator.State.PLAYING:finished.get()?VoiceTurnCoordinator.State.DIALOGUE_PENDING:VoiceTurnCoordinator.State.FINALIZING);
        when(coordinator.finishInput(anyString(),anyString(),anyString())).thenAnswer(invocation->{finished.set(true);return VoiceTurnCoordinator.State.DIALOGUE_PENDING;});
        when(coordinator.deliverDialogue("dev-1",turn.toString())).thenAnswer(invocation->{delivered.set(true);return VoiceTurnCoordinator.State.PLAYING;});
        when(coordinator.finalText("dev-1",turn.toString())).thenReturn("fixture answer");
        when(coordinator.command("dev-1",turn.toString())).thenReturn(new com.example.agentvoice.output.DeviceCommand("cmd-1","dev-1",turn.toString(),com.example.agentvoice.output.DeviceCommand.Type.PLAY_AUDIO,1,Instant.now().plusSeconds(30),Map.of("fixture",true)));
        var playback=mock(VoicePlaybackService.class);when(playback.complete("dev-1",turn.toString(),1,"cmd-1")).thenReturn(new VoicePlaybackService.Completion(VoicePlaybackService.Result.APPLIED,null));
        var gateway=new DeviceGateway(objectMapper(),turns,new ResumeService(turns),auth,coordinator,playback,new com.example.agentvoice.protocol.BoardAdapterRegistry(java.util.List.of(new com.example.agentvoice.protocol.ModelAAdapter())),mock(com.example.agentvoice.output.CommandDispatcher.class),inlineWorker(turns,coordinator),4,960000,64,1000,"MODEL_A");
        gateway.afterConnectionEstablished(session);
        gateway.handleMessage(session,new TextMessage("{\"version\":1,\"type\":\"start\",\"clientRequestId\":\"req-1\",\"payload\":{\"format\":{\"codec\":1,\"sampleRate\":16000,\"channels\":1,\"frameDurationMs\":20}}}"));
        var frame=new AudioFrameCodec.Frame(turn,attempt,0,0,20,1,16000,1,true,new byte[640]);gateway.handleMessage(session,new org.springframework.web.socket.BinaryMessage(AudioFrameCodec.encode(frame)));
        gateway.handleMessage(session,new TextMessage("{\"version\":1,\"type\":\"end\",\"payload\":{\"turnId\":\""+turn+"\",\"lastSeq\":0}}"));
        verify(turns).persistFinal(eq("dev-1"),eq(turn.toString()),eq(attempt.toString()),eq(1L),eq("fixture answer"));
        var order=inOrder(turns,coordinator);order.verify(turns).persistFinal(eq("dev-1"),eq(turn.toString()),eq(attempt.toString()),eq(1L),eq("fixture answer"));order.verify(coordinator).deliverDialogue("dev-1",turn.toString());
        gateway.handleMessage(session,new TextMessage("{\"version\":1,\"type\":\"command_ack\",\"payload\":{\"turnId\":\""+turn+"\",\"commandId\":\"cmd-1\"}}"));
        gateway.handleMessage(session,new TextMessage("{\"version\":1,\"type\":\"playback_finished\",\"payload\":{\"turnId\":\""+turn+"\",\"commandId\":\"cmd-1\"}}"));
        verify(coordinator).commandAck("dev-1",turn.toString(),"cmd-1");verify(playback).complete("dev-1",turn.toString(),1,"cmd-1");verify(coordinator).playbackFinished("dev-1",turn.toString(),"cmd-1");
        verify(turns,never()).acknowledgeFinal("dev-1",turn.toString(),1);
    }

    @Test void autoListenCreatesAFreshTurnAfterPlayback()throws Exception{
        var turns=mock(VoiceTurnService.class);var auth=mock(DeviceAuthService.class);var coordinator=mock(VoiceTurnCoordinator.class);var commands=mock(com.example.agentvoice.output.CommandDispatcher.class);var session=mock(WebSocketSession.class);
        var attrs=new HashMap<String,Object>();attrs.put("deviceId","dev-1");attrs.put("deviceToken","test-token");when(auth.authenticate("test-token")).thenReturn("dev-1");when(session.getAttributes()).thenReturn(attrs);when(session.getId()).thenReturn("ws-auto");when(session.isOpen()).thenReturn(true);
        UUID turnId=UUID.randomUUID(),attemptId=UUID.randomUUID(),nextTurn=UUID.randomUUID(),nextAttempt=UUID.randomUUID();
        var first=new VoiceTurnService.Turn(turnId.toString(),attemptId.toString(),"R".repeat(43),1,Instant.now().plusSeconds(30).toString(),"RECORDING",null,"CONTINUE");
        var second=new VoiceTurnService.Turn(nextTurn.toString(),nextAttempt.toString(),"S".repeat(43),2,Instant.now().plusSeconds(30).toString(),"RECORDING",null,"CONTINUE");
        when(turns.start(eq("dev-1"),eq("req-1"),any())).thenReturn(first);when(turns.start(eq("dev-1"),eq("auto-"+turnId),any())).thenReturn(second);when(turns.ownsTurn(eq("dev-1"),anyString(),anyLong())).thenReturn(true);
        when(turns.persistFinal(eq("dev-1"),eq(turnId.toString()),eq(attemptId.toString()),eq(1L),anyString())).thenReturn(new VoiceTurnService.Turn(turnId.toString(),attemptId.toString(),null,1,first.deadlineAt(),"FINAL","fixture answer","FINAL_SAVED"));
        when(coordinator.finalText("dev-1",turnId.toString())).thenReturn("fixture answer");
        var finished=new java.util.concurrent.atomic.AtomicBoolean();var playing=new java.util.concurrent.atomic.AtomicBoolean();var delivered=new java.util.concurrent.atomic.AtomicBoolean();doAnswer(i->{playing.set(true);return null;}).when(coordinator).end(eq("dev-1"),eq(turnId.toString()),eq(attemptId.toString()));
        when(coordinator.state("dev-1")).thenAnswer(i->!playing.get()?VoiceTurnCoordinator.State.LISTENING:delivered.get()?VoiceTurnCoordinator.State.PLAYING:finished.get()?VoiceTurnCoordinator.State.DIALOGUE_PENDING:VoiceTurnCoordinator.State.FINALIZING);
        when(coordinator.finishInput(anyString(),anyString(),anyString())).thenAnswer(invocation->{finished.set(true);return VoiceTurnCoordinator.State.DIALOGUE_PENDING;});
        when(coordinator.deliverDialogue("dev-1",turnId.toString())).thenAnswer(i->{delivered.set(true);return VoiceTurnCoordinator.State.PLAYING;});
        when(coordinator.command("dev-1",turnId.toString())).thenReturn(new com.example.agentvoice.output.DeviceCommand("cmd-1","dev-1",turnId.toString(),com.example.agentvoice.output.DeviceCommand.Type.PLAY_AUDIO,1,Instant.now().plusSeconds(30),Map.of("fixture",true)));
        when(coordinator.reopenPolicy()).thenReturn(VoiceTurnCoordinator.ReopenPolicy.AUTO_LISTEN);
        var playback=mock(VoicePlaybackService.class);when(playback.complete("dev-1",turnId.toString(),1,"cmd-1")).thenReturn(new VoicePlaybackService.Completion(VoicePlaybackService.Result.APPLIED,null));
        var gateway=new DeviceGateway(objectMapper(),turns,new ResumeService(turns),auth,coordinator,playback,new com.example.agentvoice.protocol.BoardAdapterRegistry(java.util.List.of(new com.example.agentvoice.protocol.ModelAAdapter())),commands,inlineWorker(turns,coordinator),4,960000,64,1000,"MODEL_A");
        gateway.handleMessage(session,new TextMessage("{\"version\":1,\"type\":\"start\",\"clientRequestId\":\"req-1\",\"payload\":{\"format\":{\"codec\":1,\"sampleRate\":16000,\"channels\":1,\"frameDurationMs\":20}}}"));
        var frame=new AudioFrameCodec.Frame(turnId,attemptId,0,0,20,1,16000,1,true,new byte[640]);gateway.handleMessage(session,new org.springframework.web.socket.BinaryMessage(AudioFrameCodec.encode(frame)));
        gateway.handleMessage(session,new TextMessage("{\"version\":1,\"type\":\"end\",\"payload\":{\"turnId\":\""+turnId+"\",\"lastSeq\":0}}"));
        gateway.handleMessage(session,new TextMessage("{\"version\":1,\"type\":\"command_ack\",\"payload\":{\"turnId\":\""+turnId+"\",\"commandId\":\"cmd-1\"}}"));
        gateway.handleMessage(session,new TextMessage("{\"version\":1,\"type\":\"playback_finished\",\"payload\":{\"turnId\":\""+turnId+"\",\"commandId\":\"cmd-1\"}}"));
        verify(turns).start("dev-1","auto-"+turnId,new VoiceTurnService.AudioFormat(1,16000,1,20));verify(coordinator).restart("dev-1",nextTurn.toString(),nextAttempt.toString(),"模拟识别输入");verify(commands).persist(argThat(c->c.type()==com.example.agentvoice.output.DeviceCommand.Type.START_LISTENING&&c.turnId().equals(nextTurn.toString())));
    }

    private com.example.agentvoice.task.TaskWorker inlineWorker(VoiceTurnService turns,VoiceTurnCoordinator coordinator){
        when(turns.ownsRecording(anyString(),anyString(),anyString(),anyLong())).thenReturn(true);
        when(turns.beginFinalization(anyString(),anyString(),anyString(),anyLong())).thenReturn(true);
        when(turns.ownsProcessing(anyString(),anyString(),anyString(),anyLong())).thenReturn(true);
        when(turns.finalizationTimeoutMs()).thenReturn(10_000L);
        when(coordinator.timeout(anyString(),anyLong())).thenAnswer(i->coordinator.state(i.getArgument(0)));
        var worker=mock(com.example.agentvoice.task.TaskWorker.class);
        when(worker.submitCancellable(any(Runnable.class))).thenAnswer(i->{i.getArgument(0,Runnable.class).run();return java.util.concurrent.CompletableFuture.completedFuture(null);});
        return worker;
    }

    private DeviceGateway gateway(VoiceTurnService turns,DeviceAuthService auth,VoiceTurnCoordinator coordinator){
        return new DeviceGateway(objectMapper(),turns,new ResumeService(turns),auth,coordinator,mock(VoicePlaybackService.class),
                new com.example.agentvoice.protocol.BoardAdapterRegistry(java.util.List.of(new com.example.agentvoice.protocol.ModelAAdapter())),
                mock(com.example.agentvoice.output.CommandDispatcher.class),inlineWorker(turns,coordinator),4,960000,64,1000,"MODEL_A");
    }

    private WebSocketSession session(String id){
        var session=mock(WebSocketSession.class);var attrs=new HashMap<String,Object>();attrs.put("deviceId","dev-1");attrs.put("deviceToken","test-token");
        when(session.getAttributes()).thenReturn(attrs);when(session.getId()).thenReturn(id);when(session.isOpen()).thenReturn(true);return session;
    }

    private String startMessage(String requestId){
        return "{\"version\":1,\"type\":\"start\",\"clientRequestId\":\""+requestId+"\",\"payload\":{\"format\":{\"codec\":1,\"sampleRate\":16000,\"channels\":1,\"frameDurationMs\":20}}}";
    }

    private com.fasterxml.jackson.databind.JsonNode sentEvent(WebSocketSession session)throws Exception{
        var message=org.mockito.ArgumentCaptor.forClass(TextMessage.class);verify(session).sendMessage(message.capture());
        return objectMapper().readTree(message.getValue().getPayload());
    }

    private com.fasterxml.jackson.databind.JsonNode lastSentEvent(WebSocketSession session)throws Exception{
        var message=org.mockito.ArgumentCaptor.forClass(TextMessage.class);verify(session,atLeastOnce()).sendMessage(message.capture());
        var events=message.getAllValues();return objectMapper().readTree(events.get(events.size()-1).getPayload());
    }

    private ObjectMapper objectMapper(){return new ObjectMapper().registerModule(new JavaTimeModule());}
}
