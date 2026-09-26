package com.anying.helpdesk.ticket.dto;

import com.anying.helpdesk.ticket.domain.Ticket;
import com.anying.helpdesk.ticket.domain.TicketPriority;
import com.anying.helpdesk.ticket.domain.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "工单详情")
public record TicketResponse(
        @Schema(description = "工单主键", example = "1") Long id,
        @Schema(description = "对外工单编号", example = "HD20260926011826C67762A4") String ticketNo,
        @Schema(description = "工单标题") String title,
        @Schema(description = "问题描述") String description,
        @Schema(description = "工单状态") TicketStatus status,
        @Schema(description = "工单优先级") TicketPriority priority,
        @Schema(description = "创建客户 ID", example = "1") Long creatorId,
        @Schema(description = "领取客服 ID；未领取时为空", example = "2") Long assigneeId,
        @Schema(description = "领取时间") LocalDateTime assignedAt,
        @Schema(description = "客服处理结果") String resolution,
        @Schema(description = "解决时间") LocalDateTime resolvedAt,
        @Schema(description = "取消原因") String cancelReason,
        @Schema(description = "取消时间") LocalDateTime cancelledAt,
        @Schema(description = "创建时间") LocalDateTime createdAt,
        @Schema(description = "最后更新时间") LocalDateTime updatedAt
) {

    public static TicketResponse from(Ticket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getTicketNo(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getStatus(),
                ticket.getPriority(),
                ticket.getCreatorId(),
                ticket.getAssigneeId(),
                ticket.getAssignedAt(),
                ticket.getResolution(),
                ticket.getResolvedAt(),
                ticket.getCancelReason(),
                ticket.getCancelledAt(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt()
        );
    }
}
