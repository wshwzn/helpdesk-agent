package com.anying.helpdesk.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "登录请求")
public record LoginRequest(
        @Schema(description = "登录名", example = "customer01")
        @NotBlank String username,
        @Schema(description = "登录密码", example = "Password123", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank String password
) {
}
