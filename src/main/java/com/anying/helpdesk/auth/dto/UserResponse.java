package com.anying.helpdesk.auth.dto;

import com.anying.helpdesk.auth.domain.User;
import com.anying.helpdesk.auth.domain.UserRole;

public record UserResponse(
        Long id,
        String username,
        String displayName,
        UserRole role
) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
    }
}
