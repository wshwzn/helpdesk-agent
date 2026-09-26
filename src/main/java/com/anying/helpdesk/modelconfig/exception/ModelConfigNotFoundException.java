package com.anying.helpdesk.modelconfig.exception;

public class ModelConfigNotFoundException extends RuntimeException {

    public ModelConfigNotFoundException(Long id) {
        super("model configuration not found: " + id);
    }
}
