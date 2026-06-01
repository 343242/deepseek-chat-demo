package com.smart.rag.infrastructure.stream;

public class ModelStreamException extends RuntimeException {

    public ModelStreamException(String message) {
        super(message);
    }

    public ModelStreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
