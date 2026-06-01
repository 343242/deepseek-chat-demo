package com.smart.rag.infrastructure.concurrent;

public abstract class SubtaskException extends RuntimeException {

    protected SubtaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
