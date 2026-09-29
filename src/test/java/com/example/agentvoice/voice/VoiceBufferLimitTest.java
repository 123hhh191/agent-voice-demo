package com.example.agentvoice.voice;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class VoiceBufferLimitTest {
    @Test void rejectsAudioOverConfiguredByteBudget(){UUID turn=UUID.randomUUID(),attempt=UUID.randomUUID();var state=new TurnState(turn,attempt,1,639,64,System.nanoTime());var frame=new AudioFrameCodec.Frame(turn,attempt,0,0,20,1,16000,1,true,new byte[640]);assertThrows(AudioFrameCodec.FrameException.class,()->state.accept(frame));assertEquals(0,state.audio().length);}
}
