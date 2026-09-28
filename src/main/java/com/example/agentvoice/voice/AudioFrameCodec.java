package com.example.agentvoice.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/** Fixed big-endian 56-byte header, followed by codec-native audio bytes. */
public final class AudioFrameCodec {
    public static final int HEADER_BYTES=56, MAX_PAYLOAD_BYTES=16_000;
    private AudioFrameCodec(){}
    /** Decodes and validates one complete frame, including the PCM16 payload size. */
    public static Frame decode(byte[] bytes) {
        if(bytes==null||bytes.length<HEADER_BYTES)throw new FrameException("INVALID_FRAME");
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int version=Byte.toUnsignedInt(b.get()),flags=Byte.toUnsignedInt(b.get()),headerLength=Short.toUnsignedInt(b.getShort());
        if(version!=1||(flags&0xfe)!=0||headerLength!=HEADER_BYTES)throw new FrameException("INVALID_FRAME");
        UUID turn=getUuid(b),attempt=getUuid(b);long seq=Integer.toUnsignedLong(b.getInt()),offset=Integer.toUnsignedLong(b.getInt());
        int duration=Short.toUnsignedInt(b.getShort()),codec=Byte.toUnsignedInt(b.get());long sampleRate=Integer.toUnsignedLong(b.getInt());int channels=Byte.toUnsignedInt(b.get());
        long payloadLength=Integer.toUnsignedLong(b.getInt());
        if(payloadLength>MAX_PAYLOAD_BYTES||payloadLength!=b.remaining()||duration<1||duration>100||offset+duration>30_000||sampleRate<8000||sampleRate>48000||channels<1||channels>2||codec!=1||payloadLength!=(sampleRate*channels*duration*2/1000))throw new FrameException("INVALID_FRAME");
        byte[] payload=new byte[(int)payloadLength];b.get(payload);
        return new Frame(turn,attempt,seq,offset,duration,codec,(int)sampleRate,channels,(flags&1)!=0,payload);
    }
    /** Encodes a frame using the wire header's big-endian integer representation. */
    public static byte[] encode(Frame f) {
        byte[] payload=f.payload(); if(payload.length>MAX_PAYLOAD_BYTES)throw new FrameException("INVALID_FRAME");
        ByteBuffer b=ByteBuffer.allocate(HEADER_BYTES+payload.length).order(ByteOrder.BIG_ENDIAN);
        b.put((byte)1).put((byte)(f.last()?1:0)).putShort((short)HEADER_BYTES);putUuid(b,f.turnId());putUuid(b,f.attemptId());
        b.putInt((int)f.seq()).putInt((int)f.captureOffsetMs()).putShort((short)f.durationMs()).put((byte)f.codec()).putInt(f.sampleRate()).put((byte)f.channels()).putInt(payload.length).put(payload);
        return b.array();
    }
    private static UUID getUuid(ByteBuffer b){return new UUID(b.getLong(),b.getLong());}
    private static void putUuid(ByteBuffer b,UUID u){b.putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());}
    public record Frame(UUID turnId,UUID attemptId,long seq,long captureOffsetMs,int durationMs,int codec,int sampleRate,int channels,boolean last,byte[] payload){}
    public static class FrameException extends RuntimeException {private final String code;public FrameException(String code){super(code);this.code=code;}public String code(){return code;}}
}
