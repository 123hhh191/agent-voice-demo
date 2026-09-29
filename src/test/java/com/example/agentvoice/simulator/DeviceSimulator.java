package com.example.agentvoice.simulator;

import com.example.agentvoice.voice.AudioFrameCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Deterministic protocol fixture for voice-simulator-it; payloads are synthetic PCM, not speech. */
public final class DeviceSimulator {
    public enum Scenario { NORMAL, DUPLICATE, GAP_THEN_REPLAY, DISCONNECT_AND_REPLAY, SECOND_CONNECTION }
    private DeviceSimulator(){}
    public static List<byte[]> frames(Scenario scenario,UUID turn,UUID attempt,int count){
        if(count<1||count>1500)throw new IllegalArgumentException("frame count out of range");
        List<Integer> seqs=new ArrayList<>();for(int i=0;i<count;i++)seqs.add(i);
        if(scenario==Scenario.GAP_THEN_REPLAY&&count>2){seqs.remove(Integer.valueOf(1));seqs.add(2,1);}
        if(scenario==Scenario.DISCONNECT_AND_REPLAY&&count>1){seqs.add(0,0);}
        if(scenario==Scenario.DUPLICATE&&count>0)seqs.add(0,0);
        List<byte[]> frames=new ArrayList<>();for(int seq:seqs){byte[] pcm=new byte[640];for(int i=0;i<pcm.length;i+=2){pcm[i]=0;pcm[i+1]=0x20;}pcm[0]=(byte)seq;var frame=new AudioFrameCodec.Frame(turn,attempt,seq,seq*20L,20,1,16000,1,seq==count-1,pcm);frames.add(AudioFrameCodec.encode(frame));}
        return List.copyOf(frames);
    }
}
