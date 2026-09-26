package com.anying.helpdesk.auth.dto;

import com.anying.helpdesk.auth.domain.User;
import com.anying.helpdesk.auth.domain.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "用户公开信息")
public record UserResponse(
        @Schema(description = "用户主键", example = "1") Long id,
        @Schema(description = "登录名", example = "customer01") String username,
        @Schema(description = "展示名称", example = "测试客户") String displayName,
        @Schema(description = "用户角色") UserRole role
) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
    }
}
