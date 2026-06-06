package com.smart.rag.infrastructure.messaging;

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
}
