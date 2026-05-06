package com.demo.deepseekchat.exception;

/**
 * 限流异常
 */
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
