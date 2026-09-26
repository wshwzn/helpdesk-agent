package com.anying.helpdesk.conversation.exception;

public class ConcurrentConversationUpdateException extends RuntimeException {

    public ConcurrentConversationUpdateException(Long conversationId) {
        super("conversation was updated concurrently: " + conversationId);
    }
}
