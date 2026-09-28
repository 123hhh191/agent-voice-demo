package com.example.agentvoice.voice;

import org.springframework.stereotype.Service;

@Service
@org.springframework.context.annotation.Profile("!scaffold")
public class ResumeService {
    private final VoiceTurnService turns;
    public ResumeService(VoiceTurnService turns){this.turns=turns;}
    public VoiceTurnService.Turn resume(String deviceId,String turnId,String resumeToken,boolean localEngineAlive){return turns.resume(deviceId,turnId,resumeToken,localEngineAlive);}
}
