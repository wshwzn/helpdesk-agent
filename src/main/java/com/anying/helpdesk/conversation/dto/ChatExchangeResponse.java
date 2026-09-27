package com.anying.helpdesk.conversation.dto;

import com.anying.helpdesk.agent.action.dto.PendingActionResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "一次完整的客户提问和 AI 回答")
public record ChatExchangeResponse(
        @Schema(description = "已保存的客户消息") ChatMessageResponse userMessage,
        @Schema(description = "已完成的 AI 消息") ChatMessageResponse assistantMessage,
        @Schema(description = "本次回答生成且需要客户确认的操作")
        List<PendingActionResponse> pendingActions
) {

    /**
     * 保证响应中的待确认操作集合始终只读且不为空。
     */
    public ChatExchangeResponse {
        pendingActions = pendingActions == null ? List.of() : List.copyOf(pendingActions);
    }
}
