package com.example.agentvoice.voice;

import org.springframework.stereotype.Service;

@Service
@org.springframework.context.annotation.Profile("!scaffold")
/** 封装设备语音轮次的恢复入口。 */
public class ResumeService {
    private final VoiceTurnService turns;
    public ResumeService(VoiceTurnService turns){this.turns=turns;}
    /** 校验恢复凭据并请求轮次服务接管原轮次。 */
    public VoiceTurnService.Turn resume(String deviceId,String turnId,String resumeToken,boolean localEngineAlive){return turns.resume(deviceId,turnId,resumeToken,localEngineAlive);}
}
