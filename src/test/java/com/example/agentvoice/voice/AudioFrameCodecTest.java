package com.example.agentvoice.voice;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AudioFrameCodecTest {
    private AudioFrameCodec.Frame frame(long seq,boolean last){return new AudioFrameCodec.Frame(UUID.randomUUID(),UUID.randomUUID(),seq,seq*20,20,1,16000,1,last,new byte[640]);}
    @Test void roundTripsFixedBigEndianHeaderAndPcmPayload(){var f=frame(17,true);var decoded=AudioFrameCodec.decode(AudioFrameCodec.encode(f));assertEquals(f.turnId(),decoded.turnId());assertEquals(f.attemptId(),decoded.attemptId());assertEquals(17,decoded.seq());assertTrue(decoded.last());assertArrayEquals(f.payload(),decoded.payload());assertEquals(56+640,AudioFrameCodec.encode(f).length);}
    @Test void rejectsTruncatedHeaderAndInvalidPcmLength(){assertThrows(AudioFrameCodec.FrameException.class,()->AudioFrameCodec.decode(new byte[55]));var f=new AudioFrameCodec.Frame(UUID.randomUUID(),UUID.randomUUID(),0,0,20,1,16000,1,false,new byte[639]);assertThrows(AudioFrameCodec.FrameException.class,()->AudioFrameCodec.decode(AudioFrameCodec.encode(f)));}
}
