package com.example.agentvoice.voice;

import com.example.agentvoice.asr.*;
import com.example.agentvoice.audio.*;
import com.example.agentvoice.dialogue.DialogueClient;
import com.example.agentvoice.output.CommandDispatcher;
import com.example.agentvoice.tts.MockAudioOutputAdapter;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VoiceStateMachineTest {
    private static byte[] speech(){byte[] pcm=new byte[640];for(int i=0;i<pcm.length;i+=2)pcm[i+1]=0x20;return pcm;}
    private VoiceTurnCoordinator coordinator(AtomicInteger dialogues,CommandDispatcher commands){return new VoiceTurnCoordinator(List.of(new MockAsrAAdapter(),new MockAsrBAdapter()),(d,session,t,text)->{dialogues.incrementAndGet();return new DialogueClient.DialogueReply("request",session==null?"session":session,"reply","SUCCEEDED");},new MockAudioOutputAdapter(),commands,"MOCK_A",VoiceTurnCoordinator.ReopenPolicy.WAIT_WAKEUP);}
    @Test void noSpeechFailsWithoutDialogueOrCommand(){
        AtomicInteger dialogueCalls=new AtomicInteger();CommandDispatcher commands=mock(CommandDispatcher.class);var coordinator=coordinator(dialogueCalls,commands);
        coordinator.start("d","t","a","fixture");assertEquals(VoiceTurnCoordinator.State.FAILED,coordinator.end("d","t","a"));
        assertEquals(0,dialogueCalls.get());verify(commands,never()).persist(any());assertEquals("NO_SPEECH",coordinator.failureCode("d"));
    }
    @Test void silenceAndEndFinalizeOnlyOnce(){
        AtomicInteger dialogueCalls=new AtomicInteger();CommandDispatcher commands=mock(CommandDispatcher.class);when(commands.mayDispatch(anyString())).thenReturn(true);var coordinator=coordinator(dialogueCalls,commands);
        coordinator.start("d","t","a","fixture");
        coordinator.accept(frame(0,speech()),20);
        for(int seq=1;seq<=30;seq++)coordinator.accept(frame(seq,new byte[640]),(seq+1)*20L);
        assertEquals(VoiceTurnCoordinator.State.FINALIZING,coordinator.end("d","t","a"));
        assertEquals(VoiceTurnCoordinator.State.DIALOGUE_PENDING,coordinator.finishInput("d","t","a"));
        assertEquals(VoiceTurnCoordinator.State.PLAYING,coordinator.deliverDialogue("d","t"));
        assertEquals(1,dialogueCalls.get());verify(commands,times(1)).persist(any());assertEquals("fixture",coordinator.finalText("d","t"));
    }
    @Test void playbackCompletionMustMatchAndIsIdempotent(){
        AtomicInteger dialogueCalls=new AtomicInteger();CommandDispatcher commands=mock(CommandDispatcher.class);when(commands.mayDispatch(anyString())).thenReturn(true);var coordinator=coordinator(dialogueCalls,commands);
        coordinator.start("d","t","a","fixture");coordinator.accept(frame(0,speech()),20);
        for(int seq=1;seq<=30;seq++)coordinator.accept(frame(seq,new byte[640]),(seq+1)*20L);
        coordinator.end("d","t","a");coordinator.finishInput("d","t","a");coordinator.deliverDialogue("d","t");
        String commandId=coordinator.command("d","t").commandId();

        assertEquals(VoiceTurnCoordinator.PlaybackResult.REJECTED,coordinator.playbackFinished("d","old-turn",commandId));
        assertEquals(VoiceTurnCoordinator.State.PLAYING,coordinator.state("d"));
        assertEquals(VoiceTurnCoordinator.PlaybackResult.REJECTED,coordinator.playbackFinished("d","t","wrong-command"));
        assertEquals(VoiceTurnCoordinator.PlaybackResult.APPLIED,coordinator.playbackFinished("d","t",commandId));
        assertEquals(VoiceTurnCoordinator.State.IDLE,coordinator.state("d"));
        assertEquals(VoiceTurnCoordinator.PlaybackResult.ALREADY_APPLIED,coordinator.playbackFinished("d","t",commandId));
    }
    private AudioEvent frame(long seq,byte[] pcm){return new AudioEvent("d","t","a",AudioEvent.Type.FRAME,seq,seq*20,20,AudioFormat.PCM16_MONO_16K,pcm,false);}
    @Test void endSilenceAndMaximumCompeteButFinishInputRunsOnce() throws Exception {
        var asr=mock(AsrSession.class);when(asr.submit(any())).thenReturn(List.of());
        when(asr.finishInput()).thenReturn(List.of(new AsrEvent("a","0",1,AsrEvent.Type.FINAL,"final",null)));
        var c=withAsr(asr);c.start("d","t","a","fixture");c.accept(frame(0,speech()),20);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(3);
        try{
            var end=pool.submit(()->c.end("d","t","a"));
            var max=pool.submit(()->c.timeout("d",30_000));
            var silence=pool.submit(()->{for(int i=1;i<=30;i++)c.accept(frame(i,new byte[640]),i*20);});
            end.get();max.get();silence.get();
            assertEquals(VoiceTurnCoordinator.State.FINALIZING,c.state("d"));
            c.finishInput("d","t","a");c.finishInput("d","t","a");
            verify(asr,times(1)).finishInput();verify(asr,times(1)).close();
            assertEquals(VoiceTurnCoordinator.State.DIALOGUE_PENDING,c.state("d"));
        }finally{pool.shutdownNow();}
    }
    @Test void cancellationDoesNotWaitForBlockingAsrAndLateResultCannotReviveTurn() throws Exception {
        var asr=mock(AsrSession.class);when(asr.submit(any())).thenReturn(List.of());
        var entered=new java.util.concurrent.CountDownLatch(1);var returned=new java.util.concurrent.CountDownLatch(1);
        when(asr.finishInput()).thenAnswer(i->{entered.countDown();assertTrue(returned.await(5,java.util.concurrent.TimeUnit.SECONDS));return List.of(new AsrEvent("a","0",1,AsrEvent.Type.FINAL,"late",null));});
        var c=withAsr(asr);c.start("d","t","a","fixture");c.accept(frame(0,speech()),20);c.end("d","t","a");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var finalizing=pool.submit(()->c.finishInput("d","t","a"));assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            pool.submit(()->c.cancel("d","t","a")).get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(VoiceTurnCoordinator.State.IDLE,c.state("d"));returned.countDown();finalizing.get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertNull(c.finalText("d","t"));assertEquals(VoiceTurnCoordinator.State.IDLE,c.state("d"));verify(asr,times(1)).close();
        }finally{returned.countDown();pool.shutdownNow();}
    }
    @Test void oldAttemptFinalDoesNotProduceDialogue() {
        var asr=mock(AsrSession.class);when(asr.submit(any())).thenReturn(List.of());
        when(asr.finishInput()).thenReturn(List.of(new AsrEvent("old-attempt","0",1,AsrEvent.Type.FINAL,"old",null)));
        var c=withAsr(asr);c.start("d","t","a","fixture");c.accept(frame(0,speech()),20);c.end("d","t","a");
        assertEquals(VoiceTurnCoordinator.State.FAILED,c.finishInput("d","t","a"));assertNull(c.finalText("d","t"));
    }
    @Test void monotonicClockRejectsLateFramesWithoutSleeping() {
        var clock=new java.util.concurrent.atomic.AtomicLong();var turn=UUID.randomUUID();var attempt=UUID.randomUUID();
        var state=new TurnState(turn,attempt,1,960000,64,0,clock::get);
        var f=new AudioFrameCodec.Frame(turn,attempt,0,0,20,1,16000,1,false,speech());
        clock.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
        assertEquals("TURN_DEADLINE_EXCEEDED",assertThrows(AudioFrameCodec.FrameException.class,()->state.accept(f)).code());
        state.stopInput();assertEquals("NOT_LISTENING",assertThrows(AudioFrameCodec.FrameException.class,()->state.accept(f)).code());
    }
    private VoiceTurnCoordinator withAsr(AsrSession asr){
        var provider=mock(AsrProviderAdapter.class);when(provider.id()).thenReturn("TEST");when(provider.supportedFormats()).thenReturn(java.util.Set.of(AudioFormat.PCM16_MONO_16K));when(provider.open(anyString(),anyString())).thenReturn(asr);
        return new VoiceTurnCoordinator(List.of(provider),(d,s,t,text)->{throw new AssertionError("dialogue must not run before final commit");},new MockAudioOutputAdapter(),mock(CommandDispatcher.class),"TEST",VoiceTurnCoordinator.ReopenPolicy.WAIT_WAKEUP);
    }
}
