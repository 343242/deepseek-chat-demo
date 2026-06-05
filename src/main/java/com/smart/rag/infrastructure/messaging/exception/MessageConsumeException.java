package com.smart.rag.infrastructure.messaging.exception;

/**
 * Message consume exception — consume processing failure.
 */
public class MessageConsumeException extends MessagingException {

    public MessageConsumeException(String detail, Throwable cause) {
        super(MessagingErrorCode.CONSUME_FAILED, detail, cause);
    }
}
