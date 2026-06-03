package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.ClientErrorCode;

import java.io.Serial;

/**
 * 内容过滤异常 (A类)
 */
public class ContentFilteredException extends ClientException {

    @Serial
    private static final long serialVersionUID = 54674268L;

    public ContentFilteredException(String message) {
        super(ClientErrorCode.CONTENT_FILTERED, message);
    }
}
