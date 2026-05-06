package com.demo.deepseekchat.exception;

/**
 * 限流异常
 */
public class RateLimitExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RateLimitExceededException(String message) {
        super(message);
    }
}
