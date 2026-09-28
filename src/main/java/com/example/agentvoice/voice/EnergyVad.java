package com.example.agentvoice.voice;

import com.example.agentvoice.audio.AudioEvent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/** RMS 能量近似 VAD；仅适用于规范 PCM16，阈值需用真实录音校准。 */
public final class EnergyVad implements EndpointDetector {
    private final int silenceMs, maxMs, noSpeechMs; private final double thresholdRms;
    private long silenceDuration; private boolean seenSpeech, ended;
    public EnergyVad(int silenceMs,int maxMs,int noSpeechMs,double thresholdRms) {
        if (silenceMs<1||maxMs<1||noSpeechMs<1||thresholdRms<0) throw new IllegalArgumentException("invalid endpoint settings");
        this.silenceMs=silenceMs;this.maxMs=maxMs;this.noSpeechMs=noSpeechMs;this.thresholdRms=thresholdRms;
    }
    @Override public synchronized Optional<Cause> accept(AudioEvent frame,long elapsedMs) {
        if (ended) return Optional.empty();
        if (elapsedMs>=maxMs) return Optional.of(seenSpeech?finish(Cause.MAX_DURATION):finish(Cause.NO_SPEECH));
        if (frame.type()!=AudioEvent.Type.FRAME) return Optional.empty();
        boolean speech=isSpeech(frame.payload(), thresholdRms);
        if (speech) { seenSpeech=true;silenceDuration=0; }
        else if (seenSpeech) silenceDuration+=frame.durationMs();
        if (seenSpeech&&silenceDuration>=silenceMs) return Optional.of(finish(Cause.SILENCE));
        if (!seenSpeech&&elapsedMs>=noSpeechMs) return Optional.of(finish(Cause.NO_SPEECH));
        if (frame.last()) return Optional.of(finish(seenSpeech?Cause.END:Cause.NO_SPEECH));
        return Optional.empty();
    }
    @Override public synchronized Optional<Cause> end() { return Optional.of(finish(seenSpeech?Cause.END:Cause.NO_SPEECH)); }
    @Override public synchronized Optional<Cause> timeout(long elapsedMs) {
        if (ended) return Optional.empty();
        if (seenSpeech && elapsedMs >= maxMs) return Optional.of(finish(Cause.MAX_DURATION));
        if (!seenSpeech && elapsedMs >= noSpeechMs) return Optional.of(finish(Cause.NO_SPEECH));
        return Optional.empty();
    }
    public static boolean isSpeech(byte[] pcm) { return isSpeech(pcm, 0.015); }
    private static boolean isSpeech(byte[] pcm,double threshold) { if (pcm.length<2) return false; ByteBuffer b=ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN); double squares=0;int n=0;while(b.remaining()>=2){double v=b.getShort()/32768.0;squares+=v*v;n++;}return n>0&&Math.sqrt(squares/n)>=threshold; }
    private Cause finish(Cause cause){ended=true;return cause;}
    public synchronized boolean seenSpeech(){return seenSpeech;}
}
