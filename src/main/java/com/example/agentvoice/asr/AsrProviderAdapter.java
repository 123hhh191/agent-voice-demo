package com.example.agentvoice.asr;

import com.example.agentvoice.audio.AudioFormat;
import java.util.Set;

public interface AsrProviderAdapter {
    String id();
    Set<AudioFormat> supportedFormats();
    boolean streaming();
    boolean partialResults();
    AsrSession open(String attemptId, String fixtureText);
}
