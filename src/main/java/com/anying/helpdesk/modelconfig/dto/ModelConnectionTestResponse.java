package com.anying.helpdesk.modelconfig.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "模型连接测试结果")
public record ModelConnectionTestResponse(
        @Schema(description = "连接是否成功", example = "true") boolean success,
        @Schema(description = "脱敏后的测试结果说明") String message
) {
}
