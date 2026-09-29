package com.example.agentvoice.voice;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FrameSequencerTest {
    private AudioFrameCodec.Frame frame(long seq,int value){byte[] b=new byte[640];b[0]=(byte)value;return new AudioFrameCodec.Frame(new UUID(1,1),new UUID(2,2),seq,seq*20,20,1,16000,1,false,b);}
    @Test void outputsContiguousFramesInOrder(){var s=new FrameSequencer(64);assertEquals(0,s.accept(frame(0,0)).receivedThrough());assertTrue(s.accept(frame(2,2)).contiguous().isEmpty());var out=s.accept(frame(1,1)).contiguous();assertEquals(2,out.size());assertEquals(1,out.get(0).seq());assertEquals(2,out.get(1).seq());}
    @Test void duplicatesDoNotReleaseTwiceAndConflictsFail(){var s=new FrameSequencer(64);s.accept(frame(0,1));assertTrue(s.accept(frame(0,1)).duplicate());assertThrows(AudioFrameCodec.FrameException.class,()->s.accept(frame(0,2)));}
    @Test void outOfRangeFrameIsRejected(){var s=new FrameSequencer(4);assertThrows(AudioFrameCodec.FrameException.class,()->s.accept(frame(5,1)));}
}
