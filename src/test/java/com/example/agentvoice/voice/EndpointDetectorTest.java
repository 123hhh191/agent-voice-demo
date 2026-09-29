package com.example.agentvoice.voice;

import com.example.agentvoice.audio.AudioEvent;
import com.example.agentvoice.audio.AudioFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EndpointDetectorTest {
    private AudioEvent frame(long seq,int duration,byte[] pcm){return new AudioEvent("d","t","a",AudioEvent.Type.FRAME,seq,seq*20,duration,AudioFormat.PCM16_MONO_16K,pcm,false);}
    private byte[] speech(){byte[] pcm=new byte[640];for(int i=0;i<pcm.length;i+=2){pcm[i]=0x00;pcm[i+1]=0x20;}return pcm;}
    @Test void silenceEndpointUsesAudioDurationAndDoesNotTreatMissingFramesAsSilence(){
        EnergyVad vad=new EnergyVad(600,30_000,5_000,0.015);
        assertTrue(vad.accept(frame(0,20,speech()),20).isEmpty());
        for(int i=1;i<=29;i++)assertTrue(vad.accept(frame(i,20,new byte[640]),i*20L).isEmpty());
        assertEquals(EndpointDetector.Cause.SILENCE,vad.accept(frame(30,20,new byte[640]),620).orElseThrow());
        assertTrue(vad.accept(frame(31,20,new byte[640]),640).isEmpty());
        EnergyVad noFrames=new EnergyVad(600,30_000,5_000,0.015);
        assertTrue(noFrames.end().orElseThrow()==EndpointDetector.Cause.NO_SPEECH);
    }
    @Test void noSpeechTimesOutAndHardMaximumFinalizesOnce(){
        EnergyVad vad=new EnergyVad(600,30_000,5_000,0.015);
        assertEquals(EndpointDetector.Cause.NO_SPEECH,vad.accept(frame(0,20,new byte[640]),5_000).orElseThrow());
        EnergyVad max=new EnergyVad(600,30_000,5_000,0.015);
        max.accept(frame(0,20,speech()),20);
        assertEquals(EndpointDetector.Cause.MAX_DURATION,max.accept(frame(1,20,new byte[640]),30_000).orElseThrow());
    }
}
