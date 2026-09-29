package com.example.agentvoice.tts;

import com.example.agentvoice.audio.AudioFormat;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/** 输出包含合法 RIFF/WAVE 头的静音测试资产，不代表真实语音或 TTS。 */
@Component
public final class MockAudioOutputAdapter implements AudioOutputAdapter {
    @Override public AudioFormat format(){return AudioFormat.PCM16_MONO_16K;}
    /** 生成合法 WAV 容器中的静音测试音频。 */
    @Override public AudioAsset synthesize(String text){
        byte[] pcm=new byte[1600];ByteBuffer b=ByteBuffer.allocate(44+pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36+pcm.length).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(pcm.length).put(pcm);
        return new AudioAsset(UUID.randomUUID().toString(),format(),b.array());
    }
}
