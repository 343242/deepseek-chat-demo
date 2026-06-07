package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.exception.PermanentConsumeException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * PushConsumer message listener — extracted from RocketMQMessageBus for testability.
 * <p>
 * Handles decode, business handler invocation, metrics, DLQ forwarding for permanent errors,
 * and trace propagation cleanup.
 */
class PushConsumerListener<T> {

    private static final Logger log = LoggerFactory.getLogger(PushConsumerListener.class);

    private final String topic;
    private final String group;
    private final Class<T> payloadType;
    private final MessageHandler<T> handler;
    private final MessagePayloadCodec codec;
    private final SimpleConsumerReceiveLoop.DeadLetterSender deadLetterSender;
    @Nullable private final MeterRegistry meterRegistry;
    private final TracePropagator propagator;

    PushConsumerListener(String topic, String group, Class<T> payloadType,
                          MessageHandler<T> handler, MessagePayloadCodec codec,
                          SimpleConsumerReceiveLoop.DeadLetterSender deadLetterSender,
                          @Nullable MeterRegistry meterRegistry, TracePropagator propagator) {
        this.topic = topic;
        this.group = group;
        this.payloadType = payloadType;
        this.handler = handler;
        this.codec = codec;
        this.deadLetterSender = deadLetterSender;
        this.meterRegistry = meterRegistry;
        this.propagator = propagator != null ? propagator : TracePropagator.NO_OP;
    }

    MessageListener create() {
        return messageView -> {
            propagator.restore(messageView.getProperties());
            long startNanos = meterRegistry != null ? System.nanoTime() : 0;
            try {
                T payload = codec.decode(MessagePayloadCodec.toByteArray(messageView.getBody()), payloadType);
                MessageEnvelope<T> messageEnvelope = new MessageEnvelope<>(
                    messageView.getMessageId().toString(),
                    topic,
                    messageView.getTag().orElse(null),
                    payload,
                    null,
                    messageView.getKeys().stream().findFirst().orElse(null),
                    messageView.getProperties(),
                    messageView.getBornTimestamp()
                );
                handler.onMessage(messageEnvelope);
                if (meterRegistry != null) {
                    meterRegistry.counter("messaging.consume.count",
                        "topic", topic, "group", group, "mode", "push", "result", "success").increment();
                    meterRegistry.timer("messaging.consume.latency",
                            "topic", topic, "group", group, "mode", "push")
                        .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
                }
                log.debug("Message consumed: topic={}, group={}, msgId={}",
                    topic, group, messageView.getMessageId());
                return ConsumeResult.SUCCESS;
            } catch (PermanentConsumeException e) {
                log.error("Permanent consume error, forwarding to DLQ: topic={}, msgId={}",
                    topic, messageView.getMessageId(), e);
                deadLetterSender.send(messageView, topic, group);
                return ConsumeResult.SUCCESS;
            } catch (Exception e) {
                log.error("Push consume failed: topic={}, msgId={}",
                    topic, messageView.getMessageId(), e);
                if (meterRegistry != null) {
                    meterRegistry.counter("messaging.consume.count",
                        "topic", topic, "group", group, "mode", "push", "result", "fail").increment();
                    meterRegistry.counter("messaging.retry.count",
                        "topic", topic, "group", group, "mode", "push",
                        "attempt", String.valueOf(messageView.getDeliveryAttempt())).increment();
                }
                return ConsumeResult.FAILURE;
            } finally {
                propagator.clear();
            }
        };
    }
}
