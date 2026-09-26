package com.anying.helpdesk.ticket.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "解决工单请求")
public record ResolveTicketRequest(
        @Schema(description = "客服处理结果", example = "已重置账号状态，请重新登录")
        @NotBlank @Size(max = 2000) String resolution
) {
}
