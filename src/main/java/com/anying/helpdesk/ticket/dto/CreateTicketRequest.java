package com.anying.helpdesk.ticket.dto;

import com.anying.helpdesk.ticket.domain.TicketPriority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "创建工单请求")
public record CreateTicketRequest(
        @Schema(description = "工单标题", example = "无法登录控制台")
        @NotBlank @Size(max = 100) String title,
        @Schema(description = "问题详细描述", example = "输入正确密码后仍提示登录失败")
        @NotBlank @Size(max = 2000) String description,
        @Schema(description = "工单优先级", example = "HIGH")
        @NotNull TicketPriority priority
) {
}
