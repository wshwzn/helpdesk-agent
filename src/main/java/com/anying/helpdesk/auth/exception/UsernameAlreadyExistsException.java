package com.anying.helpdesk.auth.exception;

public class UsernameAlreadyExistsException extends RuntimeException {

    public UsernameAlreadyExistsException() {
        super("username is already registered");
    }
}
