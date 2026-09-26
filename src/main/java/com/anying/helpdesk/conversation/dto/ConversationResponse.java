package com.anying.helpdesk.conversation.dto;

import com.anying.helpdesk.conversation.domain.Conversation;
import com.anying.helpdesk.conversation.domain.ConversationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "客户对话")
public record ConversationResponse(
        @Schema(description = "对话主键", example = "1") Long id,
        @Schema(description = "对话标题", example = "登录问题咨询") String title,
        @Schema(description = "对话状态") ConversationStatus status,
        @Schema(description = "最后一条消息时间") LocalDateTime lastMessageAt,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "最后更新时间") LocalDateTime updatedAt
) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getStatus(),
                conversation.getLastMessageAt(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }
}
