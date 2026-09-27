package com.anying.helpdesk.agent.action.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "修改待确认工单草稿")
public record UpdatePendingActionRequest(
        @Schema(description = "修改后的工单标题", example = "重置密码后仍无法登录")
        @NotBlank @Size(max = 100) String title,
        @Schema(description = "修改后的问题描述", example = "重置两次密码后仍提示密码错误")
        @NotBlank @Size(max = 2000) String description,
        @Schema(description = "当前乐观锁版本号", example = "0")
        @NotNull @Min(0) Integer version
) {
}
