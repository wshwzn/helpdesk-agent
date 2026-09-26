package com.anying.helpdesk.ticket.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ClaimTicketRequest(
        @NotNull @Positive Long supportId
) {
}
