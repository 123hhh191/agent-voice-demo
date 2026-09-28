package com.example.agentvoice.voice;

import com.example.agentvoice.audio.AudioEvent;
import java.util.Optional;

public interface EndpointDetector {
    enum Cause { SILENCE, MAX_DURATION, NO_SPEECH, END }
    Optional<Cause> accept(AudioEvent frame, long elapsedMs);
    Optional<Cause> end();
    Optional<Cause> timeout(long elapsedMs);
    boolean seenSpeech();
}
