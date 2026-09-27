package com.anying.helpdesk.ticket.exception;

public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(Long id) {
        super("ticket not found: " + id);
    }

    /**
     * 使用客户可见的工单号描述未找到的工单。
     */
    public TicketNotFoundException(String ticketNo) {
        super("ticket not found: " + ticketNo);
    }
}
