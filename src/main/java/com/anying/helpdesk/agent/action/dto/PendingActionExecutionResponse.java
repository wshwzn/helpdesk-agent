package com.anying.helpdesk.agent.action.dto;

import com.anying.helpdesk.ticket.dto.TicketResponse;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "待确认操作的执行结果")
public record PendingActionExecutionResponse(
        @Schema(description = "已执行的操作") PendingActionResponse action,
        @Schema(description = "创建成功的工单") TicketResponse ticket
) {
}
