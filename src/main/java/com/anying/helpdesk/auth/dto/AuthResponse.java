package com.anying.helpdesk.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "登录或注册成功后的认证信息")
public record AuthResponse(
        @Schema(description = "JWT 访问令牌") String accessToken,
        @Schema(description = "令牌类型", example = "Bearer") String tokenType,
        @Schema(description = "令牌过期时间") Instant expiresAt,
        @Schema(description = "当前用户") UserResponse user
) {
}
