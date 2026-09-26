package com.anying.helpdesk.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(min = 4, max = 32)
        @Pattern(regexp = "[A-Za-z0-9_]+", message = "must contain only letters, numbers, and underscores")
        String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 50) String displayName
) {
}
