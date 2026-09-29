package com.example.agentvoice.asr;

import com.example.agentvoice.audio.AudioFormat;
import java.util.Set;

/** 定义语音识别提供方及其支持的音频格式。 */
public interface AsrProviderAdapter {
    String id();
    Set<AudioFormat> supportedFormats();
    boolean streaming();
    boolean partialResults();
    AsrSession open(String attemptId, String fixtureText);
}
