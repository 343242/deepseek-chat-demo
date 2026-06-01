package com.smart.rag.infrastructure.concurrent;

public class ScopeClosedException extends RuntimeException {

    public ScopeClosedException(String message) {
        super(message);
    }
}
