package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.exception.PermanentConsumeException;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SimpleConsumer receive loop — extracted from RocketMQMessageBus for testability.
 * <p>
 * Owns the receive thread, processing pool, semaphore, retry counter, and all
 * error handling (permanent → DLQ, retryable → retry/DLQ).
 */
class SimpleConsumerReceiveLoop<T> {

    private static final Logger log = LoggerFactory.getLogger(SimpleConsumerReceiveLoop.class);

    @FunctionalInterface
    interface DeadLetterSender {
        boolean send(MessageView messageView, String topic, String group);
    }

    private final String topic;
    private final String group;
    private final ConsumerConfig config;
    private final Class<T> payloadType;
    private final MessageHandler<T> handler;
    private final SimpleConsumer simpleConsumer;
    private final MessagePayloadCodec codec;
    private final DeadLetterSender deadLetterSender;
    private final MessagingMetrics metrics;
    private final TracePropagator propagator;

    private final AtomicBoolean runningFlag = new AtomicBoolean(true);
    private final AtomicLong closeTimeoutMsHolder;
    @Nullable private volatile ExecutorService processingPool;

    SimpleConsumerReceiveLoop(String topic, String group, ConsumerConfig config,
                               Class<T> payloadType, MessageHandler<T> handler,
                               SimpleConsumer simpleConsumer, MessagePayloadCodec codec,
                               Duration defaultCloseTimeout, DeadLetterSender deadLetterSender,
                               MessagingMetrics metrics, TracePropagator propagator) {
        this.topic = topic;
        this.group = group;
        this.config = config;
        this.payloadType = payloadType;
        this.handler = handler;
        this.simpleConsumer = simpleConsumer;
        this.codec = codec;
        this.deadLetterSender = deadLetterSender;
        this.closeTimeoutMsHolder = new AtomicLong(defaultCloseTimeout.toMillis());
        this.metrics = metrics;
        this.propagator = propagator != null ? propagator : TracePropagator.NO_OP;
    }

    AtomicBoolean runningFlag() { return runningFlag; }
    AtomicLong closeTimeoutMsHolder() { return closeTimeoutMsHolder; }

    /**
     * Shut down the processing pool directly — called by {@link RocketMQSubscription#close}.
     * Idempotent: safe to call multiple times.
     */
    void shutdownProcessingPool() {
        ExecutorService pool = this.processingPool;
        if (pool == null) return;
        pool.shutdown();
        try {
            long timeout = closeTimeoutMsHolder.get();
            if (!pool.awaitTermination(timeout, TimeUnit.MILLISECONDS)) {
                log.warn("Processing pool did not terminate within {}ms on direct shutdown: topic={}",
                    timeout, topic);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    ExecutorService start() {
        int maxRetries = config.retryPolicy().maxRetries();
        boolean skipRetry = maxRetries <= 0;

        ConcurrentMap<String, AtomicInteger> retryCounter = skipRetry
            ? null : Caffeine.newBuilder()
                .expireAfterWrite(config.invisibleDuration().multipliedBy(2))
                .<String, AtomicInteger>build()
                .asMap();

        int processingConcurrency = Math.max(1, config.concurrency());
        AtomicInteger threadCounter = new AtomicInteger(0);
        ExecutorService pool = new ThreadPoolExecutor(
            processingConcurrency, processingConcurrency, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(config.batchSize() * 2),
            r -> new Thread(r, "simple-process-" + topic + "-" + threadCounter.incrementAndGet()),
            new ThreadPoolExecutor.AbortPolicy()
        );
        this.processingPool = pool;

        Semaphore inflightSemaphore = new Semaphore(config.concurrency());

        ExecutorService receiveExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "simple-consumer-" + topic));

        receiveExecutor.submit(() -> {
            long backoffMs = 1000;
            while (runningFlag.get()) {
                try {
                    if (!inflightSemaphore.tryAcquire(1, TimeUnit.SECONDS)) {
                        continue;
                    }
                    List<MessageView> messages = simpleConsumer.receive(
                        Math.min(config.batchSize(), config.concurrency()),
                        config.invisibleDuration());
                    backoffMs = 1000;
                    if (messages.isEmpty()) {
                        inflightSemaphore.release();
                        continue;
                    }
                    List<MessageView> processable = new ArrayList<>(messages.size());
                    processable.add(messages.getFirst());
                    for (int i = 1; i < messages.size(); i++) {
                        if (inflightSemaphore.tryAcquire()) {
                            processable.add(messages.get(i));
                        } else {
                            break;
                        }
                    }
                    for (MessageView messageView : processable) {
                        try {
                            pool.submit(() -> processMessage(
                                messageView, retryCounter, skipRetry, maxRetries, inflightSemaphore));
                        } catch (RejectedExecutionException e) {
                            inflightSemaphore.release();
                            log.warn("Processing pool full, forwarding to DLQ: topic={}, msgId={}",
                                topic, messageView.getMessageId());
                            if (deadLetterSender.send(messageView, topic, group)) {
                                try { simpleConsumer.ack(messageView); } catch (Exception ae) {
                                    log.warn("Ack failed after DLQ forward (pool full): topic={}, msgId={}",
                                        topic, messageView.getMessageId(), ae);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    if (runningFlag.get()) {
                        log.warn("Simple receive error, retrying in {}ms: topic={}", backoffMs, topic, e);
                        try {
                            long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0, Math.max(1, backoffMs / 4));
                            Thread.sleep(Math.min(backoffMs + jitter, 60_000));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        backoffMs = Math.min(backoffMs * 2, 60_000);
                    }
                }
            }
            pool.shutdown();
            try {
                long timeout = closeTimeoutMsHolder.get();
                if (!pool.awaitTermination(timeout, TimeUnit.MILLISECONDS)) {
                    log.warn("Processing pool did not terminate within {}ms: topic={}",
                        timeout, topic);
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });

        return receiveExecutor;
    }

    private void processMessage(MessageView messageView,
                                 @Nullable ConcurrentMap<String, AtomicInteger> retryCounter,
                                 boolean skipRetry, int maxRetries,
                                 Semaphore inflightSemaphore) {
        String msgId = messageView.getMessageId().toString();
        propagator.restore(messageView.getProperties());
        long startNanos = metrics.startNanos();
        try {
            T payload = codec.decode(MessagePayloadCodec.toByteArray(messageView.getBody()), payloadType);
            MessageEnvelope<T> messageEnvelope = new MessageEnvelope<>(
                msgId, topic,
                messageView.getTag().orElse(null),
                payload, null,
                messageView.getKeys().stream().findFirst().orElse(null),
                messageView.getProperties(),
                messageView.getBornTimestamp()
            );
            handler.onMessage(messageEnvelope);
            simpleConsumer.ack(messageView);
            metrics.recordConsumeSuccess(topic, group, "simple", startNanos);
            log.debug("Message consumed and acked: topic={}, group={}, msgId={}", topic, group, msgId);
            if (retryCounter != null) retryCounter.remove(msgId);
        } catch (PermanentConsumeException e) {
            metrics.recordConsumeFailure(topic, group, "simple");
            handlePermanentError(messageView, msgId, retryCounter);
        } catch (Exception e) {
            metrics.recordConsumeFailure(topic, group, "simple");
            handleRetryableError(messageView, msgId, e, retryCounter, skipRetry, maxRetries);
        } finally {
            inflightSemaphore.release();
            propagator.clear();
        }
    }

    private void handlePermanentError(MessageView messageView, String msgId,
                                       @Nullable ConcurrentMap<String, AtomicInteger> retryCounter) {
        log.error("Permanent consume error, forwarding to DLQ: topic={}, msgId={}", topic, msgId);
        if (deadLetterSender.send(messageView, topic, group)) {
            try { simpleConsumer.ack(messageView); } catch (Exception e) {
                log.warn("Ack failed after DLQ forward: topic={}, msgId={}", topic, msgId, e);
            }
        } else {
            log.warn("DLQ forward failed for permanent error, message will reappear: topic={}, msgId={}",
                topic, msgId);
        }
        if (retryCounter != null) retryCounter.remove(msgId);
    }

    private void handleRetryableError(MessageView messageView, String msgId, Exception e,
                                       @Nullable ConcurrentMap<String, AtomicInteger> retryCounter,
                                       boolean skipRetry, int maxRetries) {
        if (skipRetry || retryCounter == null) {
            log.error("Simple consume failed (no retry): topic={}, msgId={}", topic, msgId, e);
            if (deadLetterSender.send(messageView, topic, group)) {
                try { simpleConsumer.ack(messageView); } catch (Exception ae) {
                    log.warn("Ack failed after DLQ forward: topic={}, msgId={}", topic, msgId, ae);
                }
            }
        } else {
            int attempts = retryCounter.computeIfAbsent(msgId, k -> new AtomicInteger(0))
                .incrementAndGet();
            metrics.recordRetry(topic, group, "simple", String.valueOf(attempts));
            if (attempts >= maxRetries) {
                log.error("Simple consume exhausted retries ({}): topic={}, msgId={}",
                    attempts, topic, msgId, e);
                if (deadLetterSender.send(messageView, topic, group)) {
                    try { simpleConsumer.ack(messageView); } catch (Exception ae) {
                        log.warn("Ack failed after DLQ forward: topic={}, msgId={}", topic, msgId, ae);
                    }
                    retryCounter.remove(msgId);
                } else {
                    log.warn("DLQ forward failed, retry counter preserved for next delivery: topic={}, msgId={}", topic, msgId);
                }
            } else {
                log.warn("Simple consume failed ({}/{}): topic={}, msgId={}",
                    attempts, maxRetries, topic, msgId, e);
            }
        }
    }

}
