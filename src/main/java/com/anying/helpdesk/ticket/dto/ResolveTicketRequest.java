package com.anying.helpdesk.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ResolveTicketRequest(
        @NotNull @Positive Long supportId,
        @NotBlank @Size(max = 2000) String resolution
) {
}
