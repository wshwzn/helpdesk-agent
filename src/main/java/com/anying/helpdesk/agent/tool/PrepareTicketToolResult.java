package com.anying.helpdesk.agent.tool;

import com.anying.helpdesk.agent.action.domain.PendingActionStatus;
import com.anying.helpdesk.agent.action.dto.PendingActionResponse;
import com.anying.helpdesk.ticket.domain.TicketPriority;

import java.time.LocalDateTime;

/**
 * 告知模型工单草稿已进入客户确认流程，不代表真实工单已经创建。
 */
public record PrepareTicketToolResult(
        boolean success,
        boolean confirmationRequired,
        Long actionId,
        String actionType,
        String status,
        String title,
        String description,
        TicketPriority priority,
        String priorityReason,
        LocalDateTime expiresAt,
        String message
) {

    /**
     * 将待确认操作转换为模型可理解的安全工具结果。
     */
    public static PrepareTicketToolResult from(PendingActionResponse action) {
        return new PrepareTicketToolResult(
                true,
                action.status() == PendingActionStatus.PENDING,
                action.id(),
                action.actionType().name(),
                action.status().name(),
                action.title(),
                action.description(),
                action.priority(),
                action.priorityReason(),
                action.expiresAt(),
                "工单草稿已生成，必须由客户确认后才会创建真实工单"
        );
    }
}
