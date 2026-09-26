package com.anying.helpdesk.ticket.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "取消工单请求")
public record CancelTicketRequest(
        @Schema(description = "取消原因", example = "问题已经自行解决")
        @NotBlank @Size(max = 500) String reason
) {
}
