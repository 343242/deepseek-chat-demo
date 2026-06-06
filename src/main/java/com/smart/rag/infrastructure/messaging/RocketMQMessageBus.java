package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.messaging.exception.MessagePublishException;
import com.smart.rag.infrastructure.messaging.exception.MessagingErrorCode;
import com.smart.rag.infrastructure.messaging.exception.MessagingException;
import com.smart.rag.infrastructure.messaging.idempotent.IdempotentHandler;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * RocketMQ 5.x message bus implementation — direct gRPC client (NOT 4.x Remoting).
 * <p>
 * Manages Producer, PushConsumer/SimpleConsumer lifecycle, per-topic circuit breaker,
 * idempotent consumption wrapper, and dead letter forwarding.
 * All thrown exceptions are {@link MessagingException} subclasses.
 */
public class RocketMQMessageBus implements MessageBus, MessageBusManagement {

    private static final Logger log = LoggerFactory.getLogger(RocketMQMessageBus.class);

    private static final Pattern GROUP_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,128}$");
    private static final long PRODUCER_HEALTH_THRESHOLD_MS = 60_000;

    private final MessagingProperties properties;
    private final MessagePayloadCodec codec;
    private final ClientServiceProvider provider;
    private final Producer producer;
    private final ClientConfiguration clientConfiguration;

    @Nullable private final MeterRegistry meterRegistry;
    @Nullable private StringRedisTemplate redis;
    private final TracePropagator propagator;

    private final CopyOnWriteArrayList<RocketMQSubscription> activeSubscriptions = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, SendCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private volatile boolean shutdown;
    private volatile long lastSuccessfulSendMs = System.currentTimeMillis();
    @Nullable private volatile DeadLetterOperations deadLetterOps;
    private final MessageValidator validator;

    public RocketMQMessageBus(MessagingProperties properties,
                               MessagePayloadCodec codec,
                               ClientServiceProvider provider) {
        this(properties, codec, provider, null, null);
    }

    public RocketMQMessageBus(MessagingProperties properties,
                               MessagePayloadCodec codec,
                               ClientServiceProvider provider,
                               @Nullable MeterRegistry meterRegistry) {
        this(properties, codec, provider, meterRegistry, null);
    }

    public RocketMQMessageBus(MessagingProperties properties,
                               MessagePayloadCodec codec,
                               ClientServiceProvider provider,
                               @Nullable MeterRegistry meterRegistry,
                               @Nullable TracePropagator propagator) {
        this.properties = properties;
        this.codec = codec;
        this.provider = provider;
        this.meterRegistry = meterRegistry;
        this.propagator = propagator != null ? propagator : TracePropagator.NO_OP;
        this.validator = new MessageValidator(properties, codec);

        MessageValidator.validateTopicPrefix(properties.topicPrefix());

        this.clientConfiguration = ClientConfiguration.newBuilder()
            .setEndpoints(properties.rocketmq().endpoints())
            .setRequestTimeout(properties.rocketmq().requestTimeout())
            .build();

        try {
            this.producer = provider.newProducerBuilder()
                .setClientConfiguration(clientConfiguration)
                .build();
        } catch (ClientException e) {
            throw new MessagingException(MessagingErrorCode.SUBSCRIPTION_ERROR,
                "Failed to create RocketMQ Producer", e);
        }
    }

    void setRedisTemplate(@Nullable StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ==================== Send ====================

    @Override
    public String send(Message<?> message) {
        SendCircuitBreaker cb = circuitBreakerFor(message.topic());
        if (!cb.isCallAllowed()) {
            throw new MessagePublishException("Circuit breaker OPEN for topic: " + message.topic());
        }
        try {
            byte[] encoded = validator.validateAndEncode(message);
            var rmqMsg = buildRocketMQMessage(message, encoded);
            long startNanos = meterRegistry != null ? System.nanoTime() : 0;
            SendReceipt receipt = producer.send(rmqMsg);
            if (meterRegistry != null) {
                meterRegistry.timer("messaging.send.latency", "topic", message.topic())
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            }
            cb.recordSuccess();
            lastSuccessfulSendMs = System.currentTimeMillis();
            if (meterRegistry != null) {
                meterRegistry.counter("messaging.send.count",
                    "topic", message.topic(), "result", "success").increment();
            }
            log.debug("Message sent: topic={}, msgId={}", message.topic(), receipt.getMessageId());
            return receipt.getMessageId().toString();
        } catch (Exception e) {
            cb.recordFailure();
            if (meterRegistry != null) {
                meterRegistry.counter("messaging.send.count",
                    "topic", message.topic(), "result", "fail").increment();
            }
            throw new MessagePublishException("Failed to send message to topic: " + message.topic(), e);
        }
    }

    @Override
    public CompletableFuture<String> sendAsync(Message<?> message) {
        byte[] encoded = validator.validateAndEncode(message);
        var rmqMsg = buildRocketMQMessage(message, encoded);
        SendCircuitBreaker cb = circuitBreakerFor(message.topic());
        if (!cb.isCallAllowed()) {
            return CompletableFuture.failedFuture(
                new MessagePublishException("Circuit breaker OPEN for topic: " + message.topic()));
        }
        long startNanos = meterRegistry != null ? System.nanoTime() : 0;
        try {
            return producer.sendAsync(rmqMsg)
                .handle((receipt, ex) -> {
                    if (ex != null) {
                        cb.recordFailure();
                        if (meterRegistry != null) {
                            meterRegistry.counter("messaging.send.count",
                                "topic", message.topic(), "result", "fail").increment();
                        }
                        Throwable cause = (ex instanceof CompletionException ce) ? ce.getCause() : ex;
                        throw new MessagePublishException("Async send failed: " + message.topic(), cause);
                    }
                    try {
                        cb.recordSuccess();
                        lastSuccessfulSendMs = System.currentTimeMillis();
                    } catch (Exception cbEx) {
                        log.warn("Circuit breaker recordSuccess failed", cbEx);
                    }
                    if (meterRegistry != null) {
                        meterRegistry.counter("messaging.send.count",
                            "topic", message.topic(), "result", "success").increment();
                        meterRegistry.timer("messaging.send.latency", "topic", message.topic())
                            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
                    }
                    return receipt.getMessageId().toString();
                });
        } catch (Exception e) {
            cb.recordFailure();
            if (meterRegistry != null) {
                meterRegistry.counter("messaging.send.count",
                    "topic", message.topic(), "result", "fail").increment();
            }
            return CompletableFuture.failedFuture(
                new MessagePublishException("Failed to initiate async send: " + message.topic(), e));
        }
    }

    // ==================== Subscribe ====================

    @Override
    public <T> Subscription subscribe(String topic, String group,
                                       ConsumerConfig config,
                                       Class<T> payloadType,
                                       MessageHandler<T> handler) {
        if (group == null || !GROUP_PATTERN.matcher(group).matches()) {
            throw new IllegalArgumentException(
                "Invalid consumer group: '" + group
                + "'. Must be 1-128 chars, alphanumeric/underscore/hyphen only.");
        }
        synchronized (this) {
            if (shutdown) {
                throw new MessagingException(MessagingErrorCode.SUBSCRIPTION_ERROR,
                    "MessageBus is shutting down, cannot create new subscriptions");
            }
        }

        MessageHandler<T> wrappedHandler = properties.idempotent().enabled() && redis != null
            ? IdempotentHandler.wrap(handler, topic, redis, properties.idempotent().ttlSeconds(), meterRegistry)
            : handler;

        String fullTopic = properties.topicPrefix() + topic;
        FilterExpression filterExpression = new FilterExpression(
            config.tagExpression(), FilterExpressionType.TAG);
        Map<String, FilterExpression> subscriptionExpressions = Map.of(fullTopic, filterExpression);

        try {
            RocketMQSubscription subscription;
            if (config.consumerMode() == ConsumerMode.SIMPLE) {
                subscription = createSimpleSubscription(topic, group, config,
                    payloadType, wrappedHandler, subscriptionExpressions);
            } else {
                subscription = createPushSubscription(topic, group, config,
                    payloadType, wrappedHandler, subscriptionExpressions);
            }
            synchronized (this) {
                if (shutdown) {
                    try { subscription.close(); } catch (Exception e) {
                        log.warn("Failed to close subscription during shutdown race", e);
                    }
                    throw new MessagingException(MessagingErrorCode.SUBSCRIPTION_ERROR,
                        "MessageBus is shutting down, cannot create new subscriptions");
                }
                activeSubscriptions.add(subscription);
            }
            return subscription;
        } catch (ClientException e) {
            throw new MessagingException(MessagingErrorCode.SUBSCRIPTION_ERROR,
                "Failed to create subscription: " + topic, e);
        }
    }

    // ==================== PushConsumer ====================

    private <T> RocketMQSubscription createPushSubscription(
            String topic, String group, ConsumerConfig config,
            Class<T> payloadType, MessageHandler<T> handler,
            Map<String, FilterExpression> subscriptionExpressions) throws ClientException {

        var pushListener = new PushConsumerListener<>(
            topic, group, payloadType, handler, codec,
            this::sendToDeadLetter, meterRegistry, propagator).create();

        PushConsumer pushConsumer = provider.newPushConsumerBuilder()
            .setClientConfiguration(clientConfiguration)
            .setConsumerGroup(group)
            .setSubscriptionExpressions(subscriptionExpressions)
            .setConsumptionThreadCount(config.concurrency())
            .setMessageListener(pushListener)
            .build();

        return new RocketMQSubscription(topic, group, pushConsumer, null, null);
    }

    // ==================== SimpleConsumer ====================

    private <T> RocketMQSubscription createSimpleSubscription(
            String topic, String group, ConsumerConfig config,
            Class<T> payloadType, MessageHandler<T> handler,
            Map<String, FilterExpression> subscriptionExpressions) throws ClientException {

        SimpleConsumer simpleConsumer = provider.newSimpleConsumerBuilder()
            .setClientConfiguration(clientConfiguration)
            .setConsumerGroup(group)
            .setAwaitDuration(Duration.ofSeconds(30))
            .setSubscriptionExpressions(subscriptionExpressions)
            .build();

        try {
            return buildSimpleSubscription(topic, group, config,
                payloadType, handler, simpleConsumer);
        } catch (Exception e) {
            try { simpleConsumer.close(); } catch (Exception closeEx) {
                log.warn("Failed to close simpleConsumer after setup failure", closeEx);
            }
            throw new MessagingException(MessagingErrorCode.SUBSCRIPTION_ERROR,
                "Failed to create subscription: " + topic, e);
        }
    }

    private <T> RocketMQSubscription buildSimpleSubscription(
            String topic, String group, ConsumerConfig config,
            Class<T> payloadType, MessageHandler<T> handler,
            SimpleConsumer simpleConsumer) {

        SimpleConsumerReceiveLoop<T> loop = new SimpleConsumerReceiveLoop<>(
            topic, group, config, payloadType, handler, simpleConsumer,
            codec, properties.shutdownTimeout(), this::sendToDeadLetter,
            meterRegistry, propagator);

        ExecutorService receiveExecutor = loop.start();

        return new RocketMQSubscription(
            topic, group, null, simpleConsumer, receiveExecutor, loop,
            loop.runningFlag(), loop.closeTimeoutMsHolder());
    }

    // ==================== Dead Letter ====================

    boolean sendToDeadLetter(MessageView messageView, String topic, String group) {
        String msgId = messageView.getMessageId().toString();
        try {
            String dlqTopic = "%DLQ%" + group;
            var dlqMsg =
                provider.newMessageBuilder()
                    .setTopic(dlqTopic)
                    .setBody(MessagePayloadCodec.toByteArray(messageView.getBody()))
                    .setKeys(msgId)
                    .addProperty("originalTopic", topic)
                    .addProperty("originalGroup", group)
                    .addProperty("deadAt", Instant.now().toString())
                    .build();
            producer.send(dlqMsg);
            log.warn("Message forwarded to DLQ: dlqTopic={}, originalTopic={}, msgId={}",
                dlqTopic, topic, msgId);
            if (meterRegistry != null) {
                meterRegistry.counter("messaging.dead.count", "topic", topic, "group", group).increment();
            }
            return true;
        } catch (Exception e) {
            String errorType = e.getClass().getSimpleName();
            if (e instanceof java.util.concurrent.TimeoutException || e.getCause() instanceof java.util.concurrent.TimeoutException) {
                errorType = "TIMEOUT";
            } else if (e instanceof IOException) {
                errorType = "IO_ERROR";
            }
            log.error("Failed to forward message to DLQ [{}]: topic={}, msgId={}",
                errorType, topic, msgId, e);
            return false;
        }
    }

    // ==================== Shutdown ====================

    @Override
    public synchronized void shutdown() {
        if (shutdown) {
            return;
        }
        if (producer == null && activeSubscriptions.isEmpty()) {
            shutdown = true;
            return;
        }

        Duration total = properties.shutdownTimeout();
        shutdown = true;

        Duration subscriptionTimeout = total.multipliedBy(70).dividedBy(100);
        Instant deadline = Instant.now().plus(subscriptionTimeout);

        for (RocketMQSubscription sub : activeSubscriptions) {
            long remaining = Duration.between(Instant.now(), deadline).toMillis();
            if (remaining <= 0) {
                log.warn("Shutdown timeout exhausted, skipping remaining subscriptions");
                break;
            }
            sub.close(Duration.ofMillis(remaining));
        }
        activeSubscriptions.clear();

        if (producer != null) {
            try {
                producer.close();
            } catch (IOException e) {
                log.warn("Error closing producer", e);
            }
        }
    }

    // ==================== Transaction Integration ====================

    @Override
    public void sendAfterCommit(Message<?> message) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        send(message);
                    } catch (Exception e) {
                        log.error("Post-commit send failed: topic={}, dedupKey={}",
                            message.topic(), message.deduplicationKey(), e);
                        if (meterRegistry != null) {
                            meterRegistry.counter("messaging.send.post_commit_fail",
                                "topic", message.topic()).increment();
                        }
                    }
                }
            });
        } else {
            send(message);
        }
    }

    // ==================== Dead Letter Operations ====================

    @Override
    public @Nullable DeadLetterOperations deadLetterOperations() {
        if (deadLetterOps == null) {
            synchronized (this) {
                if (deadLetterOps == null) {
                    deadLetterOps = new DeadLetterOperations() {
                        @Override
                        public List<Message<?>> scanDeadLetters(String topic, int count) {
                            log.warn("DLQ scan not yet implemented for topic={}", topic);
                            return Collections.emptyList();
                        }

                        @Override
                        public void replayDeadLetter(String topic, String messageId) {
                            log.warn("DLQ replay not yet implemented for topic={}, msgId={}", topic, messageId);
                        }

                        @Override
                        public int deadLetterCount(String topic) {
                            return 0;
                        }
                    };
                }
            }
        }
        return deadLetterOps;
    }

    // ==================== Management ====================

    @Override
    public boolean isProducerHealthy() {
        return !shutdown && producer != null
            && (System.currentTimeMillis() - lastSuccessfulSendMs < PRODUCER_HEALTH_THRESHOLD_MS);
    }

    @Override
    public int activeSubscriptionCount() {
        return (int) activeSubscriptions.stream().filter(RocketMQSubscription::isActive).count();
    }

    @Override
    public Map<String, String> circuitBreakerState() {
        if (circuitBreakers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new java.util.LinkedHashMap<>();
        circuitBreakers.forEach((topic, cb) ->
            result.put(topic, cb.state().name().toLowerCase()));
        return result;
    }

    // ==================== Private Helpers ====================

    private SendCircuitBreaker circuitBreakerFor(String topic) {
        return circuitBreakers.computeIfAbsent(topic,
            k -> new SendCircuitBreaker(properties.circuitBreaker()));
    }

    private org.apache.rocketmq.client.apis.message.Message buildRocketMQMessage(
            Message<?> message, byte[] encodedPayload) {
        var builder = provider.newMessageBuilder()
            .setTopic(properties.topicPrefix() + message.topic())
            .setBody(encodedPayload);

        if (message.tag() != null) {
            builder.setTag(message.tag());
        }
        if (message.deduplicationKey() != null) {
            builder.setKeys(message.deduplicationKey());
        }
        if (message.hashKey() != null && isOrderedTopic(message.topic())) {
            builder.setMessageGroup(message.hashKey());
        }
        if (!message.headers().containsKey("Content-Type")) {
            builder.addProperty("Content-Type", "application/json");
        }
        message.headers().forEach(builder::addProperty);
        propagator.inject().forEach(builder::addProperty);

        return builder.build();
    }

    private boolean isOrderedTopic(String topic) {
        return properties.orderedTopics() != null
            && properties.orderedTopics().contains(topic);
    }

}
