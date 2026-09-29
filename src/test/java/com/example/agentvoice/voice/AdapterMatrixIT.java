package com.example.agentvoice.voice;

import com.example.agentvoice.asr.*;
import com.example.agentvoice.audio.*;
import com.example.agentvoice.dialogue.DialogueClient;
import com.example.agentvoice.output.CommandDispatcher;
import com.example.agentvoice.protocol.*;
import com.example.agentvoice.tts.MockAudioOutputAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 本地适配器组合演示；音频为合成测试 PCM，文本由 ASR fixture 提供。 */
class AdapterMatrixIT {
    @Test void modelAAndModelBEachRunWithBothMockAsrProviders() throws Exception {
        var objectMapper=new ObjectMapper();var boardA=new ModelAAdapter();var boardB=new ModelBAdapter(objectMapper);
        List<AsrProviderAdapter> providers=List.of(new MockAsrAAdapter(),new MockAsrBAdapter());
        for(BoardProtocolAdapter board:List.of(boardA,boardB))for(AsrProviderAdapter provider:providers){
            CommandDispatcher commands=mock(CommandDispatcher.class);
            when(commands.acknowledge(anyString())).thenReturn(true);
            when(commands.acknowledge(anyString(),anyString(),anyString())).thenReturn(true);
            when(commands.mayDispatch(anyString())).thenReturn(true);
            VoiceTurnCoordinator coordinator=new VoiceTurnCoordinator(providers,(device,session,turn,text)->new DialogueClient.DialogueReply("req-"+turn,session==null?"session-1":session,"mock reply","SUCCEEDED"),new MockAudioOutputAdapter(),commands,provider.id(),VoiceTurnCoordinator.ReopenPolicy.WAIT_WAKEUP);
            String turn=UUID.randomUUID().toString(),attempt=UUID.randomUUID().toString();coordinator.start("device",turn,attempt,"fixture speech");
            for(int seq=0;seq<3;seq++){
                byte[] pcm=new byte[640];for(int i=0;i<pcm.length;i+=2){pcm[i]=0;pcm[i+1]=0x20;}
                byte[] wire;
                if(board instanceof ModelAAdapter)wire=AudioFrameCodec.encode(new AudioFrameCodec.Frame(UUID.fromString(turn),UUID.fromString(attempt),seq,seq*20L,20,1,16000,1,false,pcm));
                else wire=objectMapper.writeValueAsBytes(Map.ofEntries(Map.entry("type","FRAME"),Map.entry("turnId",turn),Map.entry("attemptId",attempt),Map.entry("seq",seq),Map.entry("captureOffsetMs",seq*20),Map.entry("durationMs",20),Map.entry("codec","PCM_S16LE"),Map.entry("sampleRate",16000),Map.entry("channels",1),Map.entry("bitsPerSample",16),Map.entry("payload",Base64.getEncoder().encodeToString(pcm)),Map.entry("last",false)));
                AudioEvent event=board.decodeAudio("device",wire);assertEquals("device",event.deviceId());coordinator.accept(event,(seq+1)*20L);
            }
            assertEquals(VoiceTurnCoordinator.State.FINALIZING,coordinator.end("device",turn,attempt));
            assertEquals(VoiceTurnCoordinator.State.DIALOGUE_PENDING,coordinator.finishInput("device",turn,attempt));
            assertEquals(VoiceTurnCoordinator.State.PLAYING,coordinator.deliverDialogue("device",turn));
            assertEquals("fixture speech",coordinator.finalText("device",turn));assertNotNull(coordinator.command("device",turn));
            verify(commands).persist(any());coordinator.commandAck("device",turn,coordinator.command("device",turn).commandId());
            String commandId=coordinator.command("device",turn).commandId();
            assertEquals(VoiceTurnCoordinator.PlaybackResult.APPLIED,coordinator.playbackFinished("device",turn,commandId));
            assertEquals(VoiceTurnCoordinator.PlaybackResult.ALREADY_APPLIED,coordinator.playbackFinished("device",turn,commandId));
            assertEquals(VoiceTurnCoordinator.State.IDLE,coordinator.state("device"));
        }
    }
}
