package com.example.agentvoice.asr;

import com.example.agentvoice.audio.AudioEvent;
import java.util.List;

/** 表示单个 attempt 的识别会话及其收尾生命周期。 */
public interface AsrSession extends AutoCloseable {
    List<AsrEvent> submit(AudioEvent frame);
    List<AsrEvent> finishInput();
    void cancel();
    @Override void close();
}
