package com.anying.helpdesk.conversation.exception;

public class ChatGenerationException extends RuntimeException {

    public static final String USER_MESSAGE = "AI response generation failed; please try again later";

    public ChatGenerationException() {
        super(USER_MESSAGE);
    }
}
