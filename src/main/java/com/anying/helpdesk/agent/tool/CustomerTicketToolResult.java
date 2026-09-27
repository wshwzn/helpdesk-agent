package com.anying.helpdesk.agent.tool;

import com.anying.helpdesk.ticket.domain.TicketPriority;
import com.anying.helpdesk.ticket.domain.TicketStatus;
import com.anying.helpdesk.ticket.dto.TicketResponse;

import java.time.LocalDateTime;

/**
 * 提供给大模型的客户工单视图，排除客户 ID、客服 ID 等内部身份字段。
 */
public record CustomerTicketToolResult(
        String ticketNo,
        String title,
        String description,
        TicketStatus status,
        TicketPriority priority,
        LocalDateTime assignedAt,
        String resolution,
        LocalDateTime resolvedAt,
        String cancelReason,
        LocalDateTime cancelledAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /**
     * 将完整工单响应转换为只包含客户可见信息的工具结果。
     */
    public static CustomerTicketToolResult from(TicketResponse ticket) {
        return new CustomerTicketToolResult(
                ticket.ticketNo(),
                ticket.title(),
                ticket.description(),
                ticket.status(),
                ticket.priority(),
                ticket.assignedAt(),
                ticket.resolution(),
                ticket.resolvedAt(),
                ticket.cancelReason(),
                ticket.cancelledAt(),
                ticket.createdAt(),
                ticket.updatedAt()
        );
    }
}
