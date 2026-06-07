package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.exception.ServiceException;
import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;

import java.util.List;

/**
 * Dead letter operations — ops interface for dead letter viewing and replay.
 * <p>
 * RocketMQ implementation delegates to Broker's %DLQ% management.
 */
public interface DeadLetterOperations {
    /** Scan dead letter messages for a topic */
    List<MessageEnvelope<?>> scanDeadLetters(String topic, int count);

    /** Replay a dead letter message back to the main topic */
    void replayDeadLetter(String topic, String messageId);

    /** Get dead letter count for a topic */
    int deadLetterCount(String topic);

    /** Unsupported stub — thrown when DLQ management is not yet implemented */
    DeadLetterOperations UNSUPPORTED = new DeadLetterOperations() {
        @Override
        public List<MessageEnvelope<?>> scanDeadLetters(String topic, int count) {
            throw new ServiceException(MessagingErrorCode.UNSUPPORTED_OPERATION,
                "DLQ 扫描功能尚未实现");
        }

        @Override
        public void replayDeadLetter(String topic, String messageId) {
            throw new ServiceException(MessagingErrorCode.UNSUPPORTED_OPERATION,
                "DLQ 重放功能尚未实现");
        }

        @Override
        public int deadLetterCount(String topic) {
            throw new ServiceException(MessagingErrorCode.UNSUPPORTED_OPERATION,
                "DLQ 计数功能尚未实现");
        }
    };
}
