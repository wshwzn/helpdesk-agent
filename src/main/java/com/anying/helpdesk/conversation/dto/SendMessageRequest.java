package com.anying.helpdesk.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "发送消息请求")
public record SendMessageRequest(
        @Schema(description = "客户消息内容", example = "为什么我无法登录控制台？")
        @NotBlank @Size(max = 10000) String content
) {
}
