package com.example.agentvoice.asr;

import com.example.agentvoice.audio.AudioEvent;
import java.util.List;

public interface AsrSession extends AutoCloseable {
    List<AsrEvent> submit(AudioEvent frame);
    List<AsrEvent> finishInput();
    void cancel();
    @Override void close();
}
