package com.anying.helpdesk.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CancelTicketRequest(
        @NotNull @Positive Long customerId,
        @NotBlank @Size(max = 500) String reason
) {
}
