package com.example.agentvoice.audio;

/** 音频统一格式；PCM16 payload 使用小端序。 */
public record AudioFormat(String codec, int sampleRate, int channels, int bitsPerSample) {
    public static final AudioFormat PCM16_MONO_16K = new AudioFormat("PCM_S16LE", 16_000, 1, 16);
    public AudioFormat {
        if (codec == null || codec.isBlank() || sampleRate < 1 || channels < 1 || bitsPerSample < 1) {
            throw new IllegalArgumentException("invalid audio format");
        }
    }
}
