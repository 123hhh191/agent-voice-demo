package com.example.agentvoice.dialogue;

/** 稳定的设备语音对话边界；接入现有对话服务需先明确身份与 session 契约。 */
/** 将识别文本提交给对话服务并返回会话回复。 */
public interface DialogueClient {
    DialogueReply submit(String deviceId, String sessionId, String turnId, String finalText);
    record DialogueReply(String requestId, String sessionId, String text, String deliveryState) { }
}
