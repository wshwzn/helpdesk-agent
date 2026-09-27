package com.anying.helpdesk.agent.action.dto;

import com.anying.helpdesk.agent.action.domain.AgentPendingAction;
import com.anying.helpdesk.agent.action.domain.PendingActionStatus;
import com.anying.helpdesk.agent.action.domain.PendingActionType;
import com.anying.helpdesk.ticket.domain.TicketPriority;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "需要客户确认的 Agent 业务操作")
public record PendingActionResponse(
        @Schema(description = "待确认操作主键", example = "1") Long id,
        @Schema(description = "所属对话主键", example = "1") Long conversationId,
        @Schema(description = "生成草稿的 AI 消息主键", example = "2") Long assistantMessageId,
        @Schema(description = "操作类型") PendingActionType actionType,
        @Schema(description = "操作状态") PendingActionStatus status,
        @Schema(description = "工单草稿标题") String title,
        @Schema(description = "工单草稿描述") String description,
        @Schema(description = "AI 建议的工单优先级") TicketPriority priority,
        @Schema(description = "AI 给出的优先级理由") String priorityReason,
        @Schema(description = "执行后创建的工单主键") Long ticketId,
        @Schema(description = "确认截止时间") LocalDateTime expiresAt,
        @Schema(description = "操作执行时间") LocalDateTime executedAt,
        @Schema(description = "操作取消时间") LocalDateTime cancelledAt,
        @Schema(description = "乐观锁版本号", example = "0") Integer version,
        @Schema(description = "草稿创建时间") LocalDateTime createdAt,
        @Schema(description = "最后更新时间") LocalDateTime updatedAt
) {

    /**
     * 将待确认操作实体转换为客户可见响应。
     */
    public static PendingActionResponse from(AgentPendingAction action) {
        return new PendingActionResponse(
                action.getId(),
                action.getConversationId(),
                action.getAssistantMessageId(),
                action.getActionType(),
                action.getStatus(),
                action.getTitle(),
                action.getDescription(),
                action.getPriority(),
                action.getPriorityReason(),
                action.getTicketId(),
                action.getExpiresAt(),
                action.getExecutedAt(),
                action.getCancelledAt(),
                action.getVersion(),
                action.getCreatedAt(),
                action.getUpdatedAt()
        );
    }
}
