package com.anying.helpdesk.ticket.exception;

import com.anying.helpdesk.ticket.domain.TicketStatus;

public class InvalidTicketStatusException extends RuntimeException {

    public InvalidTicketStatusException(String operation, TicketStatus currentStatus) {
        super("cannot " + operation + " ticket in status " + currentStatus);
    }
}
