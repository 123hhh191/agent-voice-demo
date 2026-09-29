package com.example.agentvoice.asr;

import com.example.agentvoice.audio.AudioEvent;
import com.example.agentvoice.audio.AudioFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MockAsrAdapterTest {
    @Test void partialRevisionIsReplacedByFinalAndFinishIsIdempotent(){
        var adapter=new MockAsrAAdapter();var session=adapter.open("attempt","hello");
        var frame=new AudioEvent("d","t","attempt",AudioEvent.Type.FRAME,0,0,20,AudioFormat.PCM16_MONO_16K,new byte[640],false);
        var partial=session.submit(frame).get(0);var revised=session.submit(frame).get(0);var fin=session.finishInput().get(0);
        assertEquals(AsrEvent.Type.PARTIAL,partial.type());assertTrue(revised.revision()>partial.revision());
        assertEquals(AsrEvent.Type.FINAL,fin.type());assertEquals("hello",fin.text());assertTrue(session.finishInput().isEmpty());
    }
    @Test void secondMockHasNoPartials(){
        var session=new MockAsrBAdapter().open("attempt","fixture");
        assertFalse(new MockAsrBAdapter().partialResults());assertEquals(AsrEvent.Type.FINAL,session.finishInput().get(0).type());
    }
}
