package com.example.agentvoice.asr;

import com.example.agentvoice.audio.AudioEvent;
import com.example.agentvoice.audio.AudioFormat;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Set;

/** 确定性模拟识别：partial 会被更高 revision 替换，finish 才产出 final。 */
@Component
public final class MockAsrAAdapter implements AsrProviderAdapter {
    @Override public String id() { return "MOCK_A"; }
    @Override public Set<AudioFormat> supportedFormats() { return Set.of(AudioFormat.PCM16_MONO_16K); }
    @Override public boolean streaming() { return true; }
    @Override public boolean partialResults() { return true; }
    @Override public AsrSession open(String attemptId, String fixtureText) { return new Session(attemptId, fixtureText); }
    static class Session implements AsrSession {
        final String attempt, text; int frames; boolean closed, finished;
        Session(String attempt, String text) { this.attempt = attempt; this.text = text; }
        @Override public List<AsrEvent> submit(AudioEvent f) {
            if (closed || finished) return List.of(); frames++;
            if (frames == 1 && text.length() > 1) return List.of(new AsrEvent(attempt, "0", 1, AsrEvent.Type.PARTIAL, text.substring(0, Math.max(1, text.length()/2)), null));
            if (frames == 2) return List.of(new AsrEvent(attempt, "0", 2, AsrEvent.Type.PARTIAL, text, null));
            return List.of();
        }
        @Override public List<AsrEvent> finishInput() { if (closed || finished) return List.of(); finished = true; return List.of(new AsrEvent(attempt, "0", 3, AsrEvent.Type.FINAL, text, null)); }
        @Override public void cancel() { closed = true; }
        @Override public void close() { closed = true; }
    }
}
