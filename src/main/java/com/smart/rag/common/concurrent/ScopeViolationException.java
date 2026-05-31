package com.smart.rag.common.concurrent;

public class ScopeViolationException extends RuntimeException {

    public ScopeViolationException(String message) {
        super(message);
    }

    public ScopeViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
