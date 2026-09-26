package com.anying.helpdesk.auth.exception;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("username or password is incorrect");
    }
}
