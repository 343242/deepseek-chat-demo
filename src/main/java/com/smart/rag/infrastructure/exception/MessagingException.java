package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.IErrorCode;

/**
 * Messaging bus base exception — integrates with project exception hierarchy.
 */
public class MessagingException extends AbstractException {

    public MessagingException(IErrorCode errorCode) {
        super(errorCode);
    }

    public MessagingException(IErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    public MessagingException(IErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode, detail, cause);
    }
}
