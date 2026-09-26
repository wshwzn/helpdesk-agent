package com.anying.helpdesk.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "一次完整的客户提问和 AI 回答")
public record ChatExchangeResponse(
        @Schema(description = "已保存的客户消息") ChatMessageResponse userMessage,
        @Schema(description = "已完成的 AI 消息") ChatMessageResponse assistantMessage
) {
}
