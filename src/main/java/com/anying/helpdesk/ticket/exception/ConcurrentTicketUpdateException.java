package com.anying.helpdesk.ticket.exception;

public class ConcurrentTicketUpdateException extends RuntimeException {

    public ConcurrentTicketUpdateException(Long id) {
        super("ticket was updated concurrently: " + id);
    }
}
