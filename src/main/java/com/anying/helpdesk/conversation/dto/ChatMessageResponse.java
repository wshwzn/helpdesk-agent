package com.anying.helpdesk.conversation.dto;

import com.anying.helpdesk.conversation.domain.ChatMessage;
import com.anying.helpdesk.conversation.domain.ChatMessageRole;
import com.anying.helpdesk.conversation.domain.ChatMessageStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "对话消息")
public record ChatMessageResponse(
        @Schema(description = "消息主键", example = "1") Long id,
        @Schema(description = "所属对话主键", example = "1") Long conversationId,
        @Schema(description = "消息角色") ChatMessageRole role,
        @Schema(description = "消息内容；生成中或失败时可能为空") String content,
        @Schema(description = "消息生成状态") ChatMessageStatus status,
        @Schema(description = "AI 消息使用的模型配置主键") Long modelConfigId,
        @Schema(description = "AI 消息使用的模型名称") String modelName,
        @Schema(description = "输入 Token 数；厂商不返回时为空") Integer promptTokens,
        @Schema(description = "输出 Token 数；厂商不返回时为空") Integer completionTokens,
        @Schema(description = "总 Token 数；厂商不返回时为空") Integer totalTokens,
        @Schema(description = "失败错误码；成功时为空") String errorCode,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "最后更新时间") LocalDateTime updatedAt
) {

    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getRole(),
                message.getContent(),
                message.getStatus(),
                message.getModelConfigId(),
                message.getModelName(),
                message.getPromptTokens(),
                message.getCompletionTokens(),
                message.getTotalTokens(),
                message.getErrorCode(),
                message.getCreatedAt(),
                message.getUpdatedAt()
        );
    }
}
