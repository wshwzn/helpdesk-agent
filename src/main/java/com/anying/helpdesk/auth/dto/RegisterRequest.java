package com.anying.helpdesk.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "客户注册请求")
public record RegisterRequest(
        @Schema(description = "4 至 32 位字母、数字或下划线", example = "customer01")
        @NotBlank
        @Size(min = 4, max = 32)
        @Pattern(regexp = "[A-Za-z0-9_]+", message = "must contain only letters, numbers, and underscores")
        String username,
        @Schema(description = "8 至 72 位登录密码", example = "Password123", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank @Size(min = 8, max = 72) String password,
        @Schema(description = "用户展示名称", example = "测试客户")
        @NotBlank @Size(max = 50) String displayName
) {
}
