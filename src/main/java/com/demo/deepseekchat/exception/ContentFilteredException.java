package com.demo.deepseekchat.exception;

/**
 * 内容过滤异常
 */
public class ContentFilteredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ContentFilteredException(String message) {
        super(message);
    }
}
