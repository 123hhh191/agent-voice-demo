package com.example.agentvoice.audio;

/** 当前仅接受规范 PCM16；遇到需要有损/编码转换的格式时明确拒绝。 */
public final class AudioNormalizer {
    /** 校验音频帧格式与载荷长度后返回规范事件。 */
    public AudioEvent normalize(AudioEvent event) {
        if (event.format() == null || !AudioFormat.PCM16_MONO_16K.equals(event.format())) throw new UnsupportedOperationException("unsupported audio format: " + event.format());
        if (event.type() == AudioEvent.Type.FRAME && event.payload().length != event.durationMs() * 32) throw new IllegalArgumentException("PCM16 frame length does not match duration");
        return event;
    }
}
