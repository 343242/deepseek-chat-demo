package com.demo.deepseekchat.exception;

/**
 * 内容过滤异常
 */
public class ContentFilteredException extends RuntimeException {
    public ContentFilteredException(String message) {
        super(message);
    }
}
