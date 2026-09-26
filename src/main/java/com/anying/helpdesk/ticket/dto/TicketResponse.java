package com.anying.helpdesk.ticket.dto;

import com.anying.helpdesk.ticket.domain.Ticket;
import com.anying.helpdesk.ticket.domain.TicketPriority;
import com.anying.helpdesk.ticket.domain.TicketStatus;

import java.time.LocalDateTime;

public record TicketResponse(
        Long id,
        String ticketNo,
        String title,
        String description,
        TicketStatus status,
        TicketPriority priority,
        Long creatorId,
        Long assigneeId,
        LocalDateTime assignedAt,
        String resolution,
        LocalDateTime resolvedAt,
        String cancelReason,
        LocalDateTime cancelledAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
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
