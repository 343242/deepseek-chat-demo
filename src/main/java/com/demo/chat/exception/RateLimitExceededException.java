package com.demo.chat.exception;

import java.io.Serial;

/**
 * 限流异常
 */
public class RateLimitExceededException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 55234234L;

    public RateLimitExceededException(String message) {
        super(message);
    }
}
