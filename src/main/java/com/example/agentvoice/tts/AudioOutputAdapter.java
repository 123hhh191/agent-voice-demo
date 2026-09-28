package com.example.agentvoice.tts;

import com.example.agentvoice.audio.AudioFormat;
import java.util.UUID;

public interface AudioOutputAdapter {
    AudioFormat format();
    AudioAsset synthesize(String text);
    record AudioAsset(String streamId, AudioFormat format, byte[] bytes) { public AudioAsset { bytes=bytes.clone(); } @Override public byte[] bytes(){return bytes.clone();} }
}
