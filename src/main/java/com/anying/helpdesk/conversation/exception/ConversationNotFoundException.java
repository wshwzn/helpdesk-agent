package com.anying.helpdesk.conversation.exception;

public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(Long conversationId) {
        super("conversation not found: " + conversationId);
    }
}
