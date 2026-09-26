package com.anying.helpdesk.conversation.exception;

public class ConversationNotActiveException extends RuntimeException {

    public ConversationNotActiveException(Long conversationId) {
        super("conversation is not active: " + conversationId);
    }
}
