package com.anying.helpdesk.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelTicketRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
