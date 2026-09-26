package com.anying.helpdesk.conversation.exception;

public class ChatGenerationException extends RuntimeException {

    public ChatGenerationException() {
        super("AI response generation failed; please try again later");
    }
}
