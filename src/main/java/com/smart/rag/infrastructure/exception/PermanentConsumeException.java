package com.smart.rag.infrastructure.exception;

import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;

/**
 * Permanent consume exception — message is inherently unprocessable, retrying is pointless.
 * <p>
 * Typical: deserialization failure, payload schema mismatch, malformed message body.
 * PushConsumer: returns FAILURE (Broker routes to DLQ after maxDeliveryAttempts).
 * SimpleConsumer: immediate ack + forward to DLQ (skips retry counter).
 */
public class PermanentConsumeException extends MessagingException {

    public PermanentConsumeException(String message) {
        super(MessagingErrorCode.PERMANENT_CONSUME_ERROR, message);
    }

    public PermanentConsumeException(String message, Throwable cause) {
        super(MessagingErrorCode.PERMANENT_CONSUME_ERROR, message, cause);
    }
}
