package com.example.agentvoice.voice;

import com.example.agentvoice.audio.AudioEvent;
import java.util.Optional;

/** 根据音频帧和时间判断一次录音何时结束。 */
public interface EndpointDetector {
    enum Cause { SILENCE, MAX_DURATION, NO_SPEECH, END }
    Optional<Cause> accept(AudioEvent frame, long elapsedMs);
    Optional<Cause> end();
    Optional<Cause> timeout(long elapsedMs);
    boolean seenSpeech();
}
