package com.example.agentvoice.voice;

import com.example.agentvoice.common.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class VoiceFinalizationIT {
    private static final String DEVICE="it-final-"+UUID.randomUUID();
    @DynamicPropertySource static void credentials(DynamicPropertyRegistry r) {
        r.add("app.device.credentials",()->DEVICE+"="+Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired VoiceTurnService turns;
    VoiceTurnService.Turn turn;
    @BeforeEach void create() {
        jdbc.update("INSERT INTO device_registry(device_id,sn,credential_ref) VALUES(?,?,?)",DEVICE,"SN-"+UUID.randomUUID(),DEVICE);
        turn=turns.start(DEVICE,"first",new VoiceTurnService.AudioFormat(1,16000,1,20));
    }
    @AfterEach void cleanup() {
        jdbc.update("UPDATE device_registry SET active_turn_id=NULL WHERE device_id=?",DEVICE);
        for(String table:List.of("device_command","dialogue_delivery","device_dialogue_session","voice_turn","device_access_token","device_challenge","device_registry"))
            jdbc.update("DELETE FROM "+table+" WHERE device_id=?",DEVICE);
    }
    @Test void maximumRecordingDeadlineDoesNotRejectCurrentFinal() {
        assertEquals(10_000,turns.finalizationTimeoutMs());
        jdbc.update("UPDATE voice_turn SET deadline_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE turn_id=?",turn.turnId());
        assertTrue(turns.beginFinalization(DEVICE,turn.turnId(),turn.attemptId(),turn.ownerVersion()));
        assertDoesNotThrow(()->turns.persistFinal(DEVICE,turn.turnId(),turn.attemptId(),turn.ownerVersion(),"final after max"));
        assertEquals("FINAL",jdbc.queryForObject("SELECT state FROM voice_turn WHERE turn_id=?",String.class,turn.turnId()));
    }
    @Test void duplicateBeginAndFinalKeepOriginalDeadlineAndVersion() {
        assertTrue(begin());
        var deadline=jdbc.queryForObject("SELECT processing_deadline_at FROM voice_turn WHERE turn_id=?",java.sql.Timestamp.class,turn.turnId());
        assertFalse(begin());
        assertEquals(deadline,jdbc.queryForObject("SELECT processing_deadline_at FROM voice_turn WHERE turn_id=?",java.sql.Timestamp.class,turn.turnId()));
        assertEquals("first final",save("first final").finalText());
        assertEquals("first final",save("different retry text").finalText());
        assertEquals(1L,jdbc.queryForObject("SELECT final_version FROM voice_turn WHERE turn_id=?",Long.class,turn.turnId()));
    }
    @Test void processingExpiryReleasesDeviceAndRejectsLateFinal() {
        assertTrue(begin());
        jdbc.update("UPDATE voice_turn SET processing_deadline_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE turn_id=?",turn.turnId());
        assertFalse(turns.ownsProcessing(DEVICE,turn.turnId(),turn.attemptId(),turn.ownerVersion()));
        assertThrows(ApiException.class,()->save("too late"));
        turns.expireUnavailableTurns();
        assertEquals("FAILED",state());assertNull(active());
        assertThrows(ApiException.class,()->save("still late"));
        assertEquals(0L,jdbc.queryForObject("SELECT final_version FROM voice_turn WHERE turn_id=?",Long.class,turn.turnId()));
    }
    @Test void staleAttemptAndOwnerCannotBeginOrCommit() {
        assertThrows(ApiException.class,()->turns.beginFinalization(DEVICE,turn.turnId(),UUID.randomUUID().toString(),turn.ownerVersion()));
        assertThrows(ApiException.class,()->turns.beginFinalization(DEVICE,turn.turnId(),turn.attemptId(),turn.ownerVersion()+1));
        assertEquals("RECORDING",state());assertTrue(begin());
        assertThrows(ApiException.class,()->turns.persistFinal(DEVICE,turn.turnId(),UUID.randomUUID().toString(),turn.ownerVersion(),"old attempt"));
        assertThrows(ApiException.class,()->turns.persistFinal(DEVICE,turn.turnId(),turn.attemptId(),turn.ownerVersion()+1,"old owner"));
        assertEquals("PROCESSING",state());assertEquals(turn.turnId(),active());
    }
    @Test void cancelledProcessingRejectsLateFinalAndLeavesSuccessorAlone() {
        assertTrue(begin());turns.cancel(DEVICE,turn.turnId(),turn.ownerVersion());
        var next=turns.start(DEVICE,"next",new VoiceTurnService.AudioFormat(1,16000,1,20));
        assertThrows(ApiException.class,()->save("cancelled late final"));
        assertEquals("CANCELLED",state());assertEquals(next.turnId(),active());
    }
    @Test void resumeTakeoverRejectsOldResults() {
        var rebound=turns.resume(DEVICE,turn.turnId(),turn.resumeToken(),true);
        assertTrue(rebound.ownerVersion()>turn.ownerVersion());
        assertThrows(ApiException.class,()->begin());
        assertTrue(turns.beginFinalization(DEVICE,rebound.turnId(),rebound.attemptId(),rebound.ownerVersion()));
        assertThrows(ApiException.class,()->save("old socket final"));
        assertEquals("new owner final",turns.persistFinal(DEVICE,rebound.turnId(),rebound.attemptId(),rebound.ownerVersion(),"new owner final").finalText());
    }
    @Test void concurrentEndpointsHaveExactlyOneWinner() throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(3);
        var ready=new java.util.concurrent.CountDownLatch(3);var go=new java.util.concurrent.CountDownLatch(1);
        try{
            var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<3;i++)futures.add(executor.submit(()->{ready.countDown();assertTrue(go.await(5,java.util.concurrent.TimeUnit.SECONDS));return begin();}));
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));go.countDown();int wins=0;
            for(var f:futures)if(f.get(5,java.util.concurrent.TimeUnit.SECONDS))wins++;
            assertEquals(1,wins);assertEquals("PROCESSING",state());
        }finally{executor.shutdownNow();}
    }
    @Test void fullReplayChangesAttemptAndRejectsPriorEngineFinal() {
        var replay=turns.resume(DEVICE,turn.turnId(),turn.resumeToken(),false);
        assertNotEquals(turn.attemptId(),replay.attemptId());
        assertTrue(turns.beginFinalization(DEVICE,replay.turnId(),replay.attemptId(),replay.ownerVersion()));
        assertThrows(ApiException.class,()->turns.persistFinal(DEVICE,turn.turnId(),turn.attemptId(),replay.ownerVersion(),"old engine"));
        assertEquals("replayed final",turns.persistFinal(DEVICE,replay.turnId(),replay.attemptId(),replay.ownerVersion(),"replayed final").finalText());
    }
    private boolean begin(){return turns.beginFinalization(DEVICE,turn.turnId(),turn.attemptId(),turn.ownerVersion());}
    private VoiceTurnService.Turn save(String text){return turns.persistFinal(DEVICE,turn.turnId(),turn.attemptId(),turn.ownerVersion(),text);}
    private String state(){return jdbc.queryForObject("SELECT state FROM voice_turn WHERE turn_id=?",String.class,turn.turnId());}
    private String active(){return jdbc.queryForObject("SELECT active_turn_id FROM device_registry WHERE device_id=?",String.class,DEVICE);}
}
