package com.example.agentvoice.protocol;

import com.example.agentvoice.audio.AudioEvent;
import com.example.agentvoice.output.DeviceCommand;

/** 单个设备协议与统一语音模型间的映射。 */
/** 将各板卡线协议转换为统一音频事件和设备命令。 */
public interface BoardProtocolAdapter {
    String model();
    AudioEvent decodeAudio(String trustedDeviceId, byte[] wirePayload);
    byte[] encodeCommand(DeviceCommand command);
}
