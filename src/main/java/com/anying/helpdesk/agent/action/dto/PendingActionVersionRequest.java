package com.anying.helpdesk.agent.action.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "确认或取消待确认操作")
public record PendingActionVersionRequest(
        @Schema(description = "当前乐观锁版本号", example = "0")
        @NotNull @Min(0) Integer version
) {
}
