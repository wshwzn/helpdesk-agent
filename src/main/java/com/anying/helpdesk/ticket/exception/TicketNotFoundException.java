package com.anying.helpdesk.ticket.exception;

public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(Long id) {
        super("ticket not found: " + id);
    }
}
