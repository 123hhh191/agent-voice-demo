package com.example.agentvoice.voice;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded reorder buffer that only releases a contiguous prefix. */
public final class FrameSequencer {
    private final int maxBuffered;
    private final TreeMap<Long,AudioFrameCodec.Frame> waiting=new TreeMap<>();
    private final Map<Long,String> recent=new java.util.LinkedHashMap<>();
    private long receivedThrough=-1;
    public FrameSequencer(int maxBuffered){if(maxBuffered<1)throw new IllegalArgumentException();this.maxBuffered=maxBuffered;}
    /** 缓存乱序帧，只释放从下一期望序号开始的连续帧。 */
    public synchronized Result accept(AudioFrameCodec.Frame frame){
        String hash=hash(AudioFrameCodec.encode(frame));
        // 重复帧必须与已收帧字节一致，否则拒绝冲突数据。
        if(frame.seq()<=receivedThrough){String prior=recent.get(frame.seq());if(prior!=null&&!prior.equals(hash))throw new AudioFrameCodec.FrameException("FRAME_CONFLICT");return new Result(List.of(),receivedThrough,missingRanges(),true);}
        var pending=waiting.get(frame.seq());if(pending!=null){if(!hash(AudioFrameCodec.encode(pending)).equals(hash))throw new AudioFrameCodec.FrameException("FRAME_CONFLICT");return new Result(List.of(),receivedThrough,missingRanges(),true);}
        if(frame.seq()-receivedThrough>maxBuffered||waiting.size()>=maxBuffered)throw new AudioFrameCodec.FrameException("FRAME_WINDOW_EXCEEDED");
        waiting.put(frame.seq(),frame);List<AudioFrameCodec.Frame> ready=new ArrayList<>();
        while(waiting.containsKey(receivedThrough+1)){AudioFrameCodec.Frame next=waiting.remove(++receivedThrough);ready.add(next);recent.put(receivedThrough,hash(AudioFrameCodec.encode(next)));if(recent.size()>maxBuffered*2)recent.remove(recent.keySet().iterator().next());}
        return new Result(List.copyOf(ready),receivedThrough,missingRanges(),false);
    }
    public synchronized long receivedThrough(){return receivedThrough;}
    public synchronized long highestPending(){return waiting.isEmpty()?-1:waiting.lastKey();}
    /** 根据已缓存帧计算尚未收到的序号区间。 */
    public synchronized List<Range> missingRanges(){if(waiting.isEmpty())return List.of();List<Range> ranges=new ArrayList<>();long start=receivedThrough+1,prev=start;for(long seq:waiting.keySet()){if(seq>prev){ranges.add(new Range(start,seq-1));}start=seq+1;prev=seq+1;}return List.copyOf(ranges);}
    private String hash(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);}}
    public record Range(long from,long to){}
    public record Result(List<AudioFrameCodec.Frame> contiguous,long receivedThrough,List<Range> missingRanges,boolean duplicate){}
}
