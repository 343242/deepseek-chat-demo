package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;

/**
 * Message consume exception — consume processing failure.
 */
public class MessageConsumeException extends MessagingException {

    public MessageConsumeException(String detail, Throwable cause) {
        super(MessagingErrorCode.CONSUME_FAILED, detail, cause);
    }
}
