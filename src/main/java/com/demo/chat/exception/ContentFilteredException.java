package com.demo.chat.exception;

import java.io.Serial;

/**
 * 内容过滤异常
 */
public class ContentFilteredException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 54674268L;

    public ContentFilteredException(String message) {
        super(message);
    }
}
