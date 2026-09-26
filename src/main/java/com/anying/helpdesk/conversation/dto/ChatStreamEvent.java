package com.anying.helpdesk.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "流式对话事件；不同事件类型只使用与其相关的字段")
public record ChatStreamEvent(
        @Schema(description = "SSE 事件类型", allowableValues = {"start", "delta", "done", "error"})
        String type,
        @Schema(description = "start 事件携带的已保存客户消息")
        ChatMessageResponse userMessage,
        @Schema(description = "start 事件携带的待生成 AI 消息主键", example = "2")
        Long assistantMessageId,
        @Schema(description = "delta 事件携带的本次新增文本片段")
        String content,
        @Schema(description = "done 事件携带的完整 AI 消息")
        ChatMessageResponse assistantMessage,
        @Schema(description = "error 事件携带的稳定错误码")
        String errorCode,
        @Schema(description = "error 事件携带的脱敏错误说明")
        String message
) {

    /**
     * 创建流式回答开始事件，告知客户端已保存的消息编号。
     */
    public static ChatStreamEvent start(ChatMessageResponse userMessage, Long assistantMessageId) {
        return new ChatStreamEvent(
                "start",
                userMessage,
                assistantMessageId,
                null,
                null,
                null,
                null
        );
    }

    /**
     * 创建模型文本增量事件。
     */
    public static ChatStreamEvent delta(String content) {
        return new ChatStreamEvent("delta", null, null, content, null, null, null);
    }

    /**
     * 创建流式回答完成事件，并返回最终持久化的 AI 消息。
     */
    public static ChatStreamEvent done(ChatMessageResponse assistantMessage) {
        return new ChatStreamEvent("done", null, null, null, assistantMessage, null, null);
    }

    /**
     * 创建不包含厂商敏感信息的流式错误事件。
     */
    public static ChatStreamEvent error(String errorCode, String message) {
        return new ChatStreamEvent("error", null, null, null, null, errorCode, message);
    }
}
