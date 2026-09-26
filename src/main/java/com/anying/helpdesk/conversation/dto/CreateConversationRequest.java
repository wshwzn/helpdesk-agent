package com.anying.helpdesk.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "创建对话请求")
public record CreateConversationRequest(
        @Schema(description = "对话标题", example = "登录问题咨询")
        @NotBlank @Size(max = 100) String title
) {
}
