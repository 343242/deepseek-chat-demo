package com.smart.rag.common.concurrent;

public class ScopeClosedException extends RuntimeException {

    public ScopeClosedException(String message) {
        super(message);
    }
}
