package com.example.agentvoice.voice;

import com.example.agentvoice.asr.*;
import com.example.agentvoice.audio.AudioEvent;
import com.example.agentvoice.audio.AudioFormat;
import com.example.agentvoice.device.DeviceAuthService;
import com.example.agentvoice.task.TaskWorker;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real socket/MySQL lifecycle; the only simulated dependencies are ASR, dialogue and TTS. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "app.voice.asr-provider=CONTROLLED_TEST","app.voice.finalization-timeout-ms=1000",
        "app.tasks.workers=1","app.tasks.queue-capacity=1","app.voice.reopen-policy=WAIT_WAKEUP"})
class VoiceFinalizationWebSocketIT {
    private static final String DEVICE="it-ws-final-"+UUID.randomUUID();
    private static final byte[] KEY=new byte[32];
    static{new SecureRandom().nextBytes(KEY);}
    @DynamicPropertySource static void credentials(DynamicPropertyRegistry r){r.add("app.device.credentials",()->DEVICE+"="+Base64.getEncoder().encodeToString(KEY));}
    @TestConfiguration static class Config {@Bean ControlledAsr controlledAsr(){return new ControlledAsr();}}
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DeviceAuthService auth;
    @Autowired VoiceTurnCoordinator coordinator;
    @Autowired DeviceGateway gateway;
    @Autowired TaskWorker worker;
    @Autowired ControlledAsr provider;
    private final ObjectMapper mapper=new ObjectMapper();
    private final List<WebSocket> sockets=new ArrayList<>();
    private String turn,attempt,serial;private Events events;private WebSocket socket;

    @BeforeEach void create() throws Exception {
        provider.block=false;provider.error=false;provider.sessions.clear();serial="SN-"+UUID.randomUUID();
        jdbc.update("INSERT INTO device_registry(device_id,sn,credential_ref) VALUES(?,?,?)",DEVICE,serial,DEVICE);
        var challenge=auth.challenge(DEVICE,serial);long issued=Instant.now().getEpochSecond();
        String canonical="v1\n"+DEVICE+"\n"+serial+"\n"+challenge.challengeId()+"\n"+challenge.nonce()+"\n"+issued;
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(KEY,"HmacSHA256"));
        String token=auth.token(new DeviceAuthService.TokenRequest(DEVICE,serial,challenge.challengeId(),challenge.nonce(),issued,HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))))).accessToken();
        events=new Events();socket=HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization","Bearer "+token).buildAsync(URI.create("ws://127.0.0.1:"+port+"/device/voice"),events).get(5,TimeUnit.SECONDS);sockets.add(socket);
    }
    @AfterEach void cleanup() throws Exception {
        for(var s:provider.sessions.values())s.release.countDown();
        try{
            String active=active();
            if(active!=null&&socket!=null&&!socket.isOutputClosed()){control("cancel",Map.of("turnId",active));events.await("cancelled",5);}
            for(var s:sockets)if(!s.isOutputClosed())s.sendClose(WebSocket.NORMAL_CLOSURE,"test complete").get(5,TimeUnit.SECONDS);
        }finally{
            jdbc.update("UPDATE device_registry SET active_turn_id=NULL WHERE device_id=?",DEVICE);
            gateway.cleanupExpired();
            for(String table:List.of("device_command","dialogue_delivery","device_dialogue_session","voice_turn","device_access_token","device_challenge","device_registry"))jdbc.update("DELETE FROM "+table+" WHERE device_id=?",DEVICE);
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM voice_turn WHERE device_id=?",Integer.class,DEVICE));
        }
    }
    @Test void realThirtySecondDeadlineCommitsFinalAndPlaybackReceiptReleasesMemory() throws Exception {
        long start=System.nanoTime();start("max");frame(0,false);events.await("ack",5);
        JsonNode result=events.await("final",35);long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        assertTrue(elapsed>=29_500&&elapsed<34_000,"unexpected real deadline elapsed="+elapsed);
        assertEquals("FINAL",state());assertEquals(1L,version());assertEquals("controlled final",result.path("text").asText());
        assertEquals(1,provider.sessions.get(attempt).finished.get());
        assertEquals(1,provider.sessions.get(attempt).closed.get());
        String command=result.path("command").path("commandId").asText();assertFalse(command.isBlank());
        control("command_ack",Map.of("turnId",turn,"commandId",command));events.await("command_acknowledged",5);
        control("playback_finished",Map.of("turnId",turn,"commandId",command));events.await("playback_acknowledged",5);
        assertNull(active());assertMemoryReleased(turn);
        System.out.println("REAL_MAX_RECORDING elapsedMs="+elapsed+" finalVersion="+version()+" activeTurnReleased=true");
    }
    @Test void blockedAsrTimesOutWithoutLateFrameOrLateResultChangingFailure() throws Exception {
        provider.block=true;start("timeout");frame(0,true);events.await("ack",5);
        var engine=provider.sessions.get(attempt);assertTrue(engine.entered.await(5,TimeUnit.SECONDS));
        assertEquals("PROCESSING",state());frame(1,false);
        JsonNode late=events.await("error",5);assertEquals("NOT_LISTENING",late.path("code").asText());assertEquals("PROCESSING",state());
        JsonNode timeout=events.await("error",5);assertEquals("ASR_FINAL_TIMEOUT",timeout.path("code").asText());
        assertEquals("FAILED",state());assertNull(active());assertMemoryReleased(turn);
        engine.release.countDown();assertTrue(engine.returned.await(5,TimeUnit.SECONDS));
        assertEquals("FAILED",state());assertEquals(0L,version());assertEquals(1,engine.closed.get());assertNoCommands();
    }
    @Test void cancelBlockedAsrReturnsPromptlyAndLateResultCannotAffectNextTurn() throws Exception {
        provider.block=true;start("cancel");frame(0,true);events.await("ack",5);var oldEngine=provider.sessions.get(attempt);
        assertTrue(oldEngine.entered.await(5,TimeUnit.SECONDS));String oldTurn=turn;
        long began=System.nanoTime();control("cancel",Map.of("turnId",turn));events.await("cancelled",5);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)<1_000);assertMemoryReleased(oldTurn);
        provider.block=false;start("successor");String next=turn;
        oldEngine.release.countDown();assertTrue(oldEngine.returned.await(5,TimeUnit.SECONDS));
        assertEquals(next,active());assertEquals("RECORDING",state());assertEquals(0L,version());assertNoCommands();
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT state FROM voice_turn WHERE turn_id=?",String.class,oldTurn));
    }
    @Test void providerErrorFailsWithoutSubmittingDialogueOrPlayback() throws Exception {
        provider.error=true;start("provider-error");frame(0,true);events.await("ack",5);
        assertEquals("ASR_FAILED",events.await("error",5).path("code").asText());
        assertEquals("FAILED",state());assertNull(active());assertMemoryReleased(turn);assertNoCommands();
    }
    @Test void fullWorkerQueueRejectsFinalizationAndReleasesTurn() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var running=worker.submitCancellable(()->{entered.countDown();try{release.await();}catch(InterruptedException ex){Thread.currentThread().interrupt();}});
        assertTrue(entered.await(5,TimeUnit.SECONDS));var queued=worker.submitCancellable(()->{});
        try{
            start("busy");frame(0,true);events.await("ack",5);
            var error=events.await("error",5);assertEquals("ASR_BUSY",error.path("code").asText());assertTrue(error.path("retryable").asBoolean());
            assertEquals("FAILED",state());assertNull(active());assertMemoryReleased(turn);assertNoCommands();
        }finally{queued.cancel(false);release.countDown();running.get(5,TimeUnit.SECONDS);}
    }
    private void start(String request) throws Exception {
        socket.sendText(mapper.writeValueAsString(Map.of("version",1,"type","start","clientRequestId",request,"payload",Map.of("format",Map.of("codec",1,"sampleRate",16000,"channels",1,"frameDurationMs",20)))),true).join();
        JsonNode event=events.await("started",5);turn=event.path("turnId").asText();attempt=event.path("asrAttemptId").asText();
    }
    private void control(String type,Map<String,?> payload)throws Exception{socket.sendText(mapper.writeValueAsString(Map.of("version",1,"type",type,"payload",payload)),true).join();}
    private void frame(int seq,boolean last){byte[] pcm=new byte[640];for(int i=1;i<pcm.length;i+=2)pcm[i]=0x20;socket.sendBinary(ByteBuffer.wrap(AudioFrameCodec.encode(new AudioFrameCodec.Frame(UUID.fromString(turn),UUID.fromString(attempt),seq,seq*20L,20,1,16000,1,last,pcm))),true).join();}
    private String active(){return jdbc.queryForObject("SELECT active_turn_id FROM device_registry WHERE device_id=?",String.class,DEVICE);}
    private String state(){return jdbc.queryForObject("SELECT state FROM voice_turn WHERE turn_id=?",String.class,turn);}
    private long version(){return jdbc.queryForObject("SELECT final_version FROM voice_turn WHERE turn_id=?",Long.class,turn);}
    private void assertNoCommands(){assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?",Integer.class,DEVICE));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dialogue_delivery WHERE device_id=?",Integer.class,DEVICE));}
    private void assertMemoryReleased(String id){assertEquals(VoiceTurnCoordinator.State.IDLE,coordinator.state(DEVICE));for(String field:List.of("states","formats","turnDevices","deadlines","disconnectTimes","finalizations","recordingTimers"))assertFalse(((Map<?,?>)ReflectionTestUtils.getField(gateway,field)).containsKey(id),"retained "+field);}
    static class Events implements WebSocket.Listener {
        final BlockingQueue<String> queue=new LinkedBlockingQueue<>();final StringBuilder fragment=new StringBuilder();
        @Override public void onOpen(WebSocket socket){socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last){fragment.append(data);if(last){queue.add(fragment.toString());fragment.setLength(0);}socket.request(1);return null;}
        JsonNode await(String type,int seconds)throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
            while(true){String raw=queue.poll(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);assertNotNull(raw,"timed out waiting for "+type);JsonNode event=new ObjectMapper().readTree(raw);if(type.equals(event.path("type").asText()))return event;if("error".equals(event.path("type").asText()))fail("unexpected error code="+event.path("code").asText());}
        }
    }
    static class ControlledAsr implements AsrProviderAdapter {
        volatile boolean block,error;final Map<String,Engine> sessions=new ConcurrentHashMap<>();
        public String id(){return "CONTROLLED_TEST";}public Set<AudioFormat> supportedFormats(){return Set.of(AudioFormat.PCM16_MONO_16K);}public boolean streaming(){return true;}public boolean partialResults(){return true;}
        public AsrSession open(String attempt,String fixture){var engine=new Engine(attempt,block,error);sessions.put(attempt,engine);return engine;}
    }
    static class Engine implements AsrSession {
        final String attempt;final boolean block,error;final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);final AtomicInteger finished=new AtomicInteger(),closed=new AtomicInteger();
        Engine(String attempt,boolean block,boolean error){this.attempt=attempt;this.block=block;this.error=error;}
        public List<AsrEvent> submit(AudioEvent frame){return List.of();}
        public List<AsrEvent> finishInput(){finished.incrementAndGet();entered.countDown();if(block){boolean done=false;while(!done)try{done=release.await(10,TimeUnit.SECONDS);}catch(InterruptedException ignored){/* Deliberately emulate a provider ignoring cancellation. */}}returned.countDown();return List.of(new AsrEvent(attempt,"0",1,error?AsrEvent.Type.ERROR:AsrEvent.Type.FINAL,error?null:"controlled final",error?"PROVIDER_ERROR":null));}
        public void cancel(){}public void close(){closed.incrementAndGet();}
    }
}
