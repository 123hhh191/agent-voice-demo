package com.example.agentvoice.asr;

import com.example.agentvoice.audio.AudioFormat;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Set;

/** 非流式模拟 ASR：只在 finishInput 时产出最终 fixture。 */
@Component
public final class MockAsrBAdapter implements AsrProviderAdapter {
    @Override public String id() { return "MOCK_B"; }
    @Override public Set<AudioFormat> supportedFormats() { return Set.of(AudioFormat.PCM16_MONO_16K); }
    @Override public boolean streaming() { return false; }
    @Override public boolean partialResults() { return false; }
    /** 创建只在输入结束时返回识别结果的模拟会话。 */
    @Override public AsrSession open(String attemptId, String fixtureText) {
        return new AsrSession() {
            boolean closed, done;
            @Override public List<AsrEvent> submit(com.example.agentvoice.audio.AudioEvent frame) { return List.of(); }
            @Override public List<AsrEvent> finishInput() { if (closed || done) return List.of(); done=true; return List.of(new AsrEvent(attemptId,"0",1,AsrEvent.Type.FINAL,fixtureText,null)); }
            @Override public void cancel() { closed=true; }
            @Override public void close() { closed=true; }
        };
    }
}
