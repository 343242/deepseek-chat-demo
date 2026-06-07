package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;

/**
 * Message publish exception — Producer send failure.
 */
public class MessagePublishException extends MessagingException {

    public MessagePublishException(String detail, Throwable cause) {
        super(MessagingErrorCode.PUBLISH_FAILED, detail, cause);
    }

    public MessagePublishException(String detail) {
        super(MessagingErrorCode.PUBLISH_FAILED, detail);
    }
}
