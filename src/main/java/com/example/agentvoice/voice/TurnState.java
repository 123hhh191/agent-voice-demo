package com.example.agentvoice.voice;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.UUID;

/** Bounded node-local audio state; audio is intentionally not persisted to SQL or Redis. */
public final class TurnState {
    private final UUID turnId,attemptId; private volatile long ownerVersion; private final long startedNanos; private final int maxBytes;
    private final FrameSequencer sequencer; private final ByteArrayOutputStream audio=new ByteArrayOutputStream();
    private java.util.function.LongSupplier nanoClock=System::nanoTime;
    private long lastSeq=-1; private boolean ended; private long expectedLastSeq=-1; private boolean finalizing; private boolean deliverySent;
    public TurnState(UUID turnId,UUID attemptId,long ownerVersion,int maxBytes,int reorderLimit,long startedNanos){this.turnId=turnId;this.attemptId=attemptId;this.ownerVersion=ownerVersion;this.maxBytes=maxBytes;this.sequencer=new FrameSequencer(reorderLimit);this.startedNanos=startedNanos;}
    TurnState(UUID turnId,UUID attemptId,long ownerVersion,int maxBytes,int reorderLimit,long startedNanos,java.util.function.LongSupplier clock){this(turnId,attemptId,ownerVersion,maxBytes,reorderLimit,startedNanos);this.nanoClock=clock;}
    public synchronized void stopInput(){finalizing=true;}
    public long elapsedMs(){return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong()-startedNanos);}
    /** 校验帧归属、边界和容量，再按序缓存音频数据。 */
    public synchronized FrameSequencer.Result accept(AudioFrameCodec.Frame f){if(finalizing)throw new AudioFrameCodec.FrameException("NOT_LISTENING");if(!f.turnId().equals(turnId)||!f.attemptId().equals(attemptId))throw new AudioFrameCodec.FrameException("STALE_ATTEMPT");if(f.seq()>1499||f.captureOffsetMs()<0||f.durationMs()<1||f.captureOffsetMs()+f.durationMs()>30_000||f.payload().length!=(f.sampleRate()*f.channels()*2*f.durationMs()/1000))throw new AudioFrameCodec.FrameException("INVALID_FRAME");if(elapsedMs()>=30_000)throw new AudioFrameCodec.FrameException("TURN_DEADLINE_EXCEEDED");if(expectedLastSeq>=0&&(f.seq()>expectedLastSeq||f.last()!=(f.seq()==expectedLastSeq)))throw new AudioFrameCodec.FrameException("INVALID_SEQUENCE");var result=sequencer.accept(f);for(var contiguous:result.contiguous()){if(audio.size()+contiguous.payload().length>maxBytes)throw new AudioFrameCodec.FrameException("AUDIO_LIMIT_EXCEEDED");audio.writeBytes(contiguous.payload());if(contiguous.last()){ended=true;lastSeq=contiguous.seq();}}return result;}
    public synchronized byte[] audio(){return audio.toByteArray();}
    public synchronized boolean complete(){return ended&&sequencer.receivedThrough()>=lastSeq;}
    public synchronized long receivedThrough(){return sequencer.receivedThrough();}
    public synchronized void end(long seq){if(seq<0||seq>1_499)throw new AudioFrameCodec.FrameException("INVALID_SEQUENCE");if(expectedLastSeq>=0&&expectedLastSeq!=seq)throw new AudioFrameCodec.FrameException("INVALID_SEQUENCE");expectedLastSeq=seq;if(sequencer.receivedThrough()>seq||sequencer.highestPending()>seq)throw new AudioFrameCodec.FrameException("INVALID_SEQUENCE");}
    public synchronized boolean endComplete(){return expectedLastSeq>=0&&sequencer.receivedThrough()==expectedLastSeq&&ended&&lastSeq==expectedLastSeq;}
    /** 仅在 end 与全部帧齐备时允许单次进入收尾。 */
    public synchronized boolean claimFinalize(){if(finalizing||!endComplete())return false;finalizing=true;return true;}
    /** 标记最终结果已发送，避免重试时重复投递。 */
    public synchronized boolean claimDelivery(){if(deliverySent)return false;deliverySent=true;return true;}
    public synchronized long expectedLastSeq(){return expectedLastSeq;}
    public void rebind(long version){ownerVersion=version;}
    public synchronized java.util.List<FrameSequencer.Range> missingRanges(){return sequencer.missingRanges();}
    public long ownerVersion(){return ownerVersion;}public UUID turnId(){return turnId;}public UUID attemptId(){return attemptId;}public long startedNanos(){return startedNanos;}
}
