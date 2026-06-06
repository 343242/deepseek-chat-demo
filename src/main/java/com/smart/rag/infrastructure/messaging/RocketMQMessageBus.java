package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.messaging.exception.MessagePublishException;
import com.smart.rag.infrastructure.messaging.exception.MessagingErrorCode;
import com.smart.rag.infrastructure.messaging.exception.MessagingException;
import com.smart.rag.infrastructure.messaging.exception.PermanentConsumeException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.ByteBuffer;
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

    private static final Pattern TOPIC_PATTERN = Pattern.compile("^[a-zA-Z0-9_-][%a-zA-Z0-9_-]{0,127}$");
    private static final Pattern TAG_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final Pattern GROUP_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,128}$");

    private static final RedisScript<Long> IDEMPOTENT_MARK = new DefaultRedisScript<>(
        "local result = redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) " +
        "if result then return 0 end " +
        "return 1",
        Long.class
    );

    private final MessagingProperties properties;
    private final MessagePayloadCodec codec;
    private final ClientServiceProvider provider;
    private final Producer producer;
    private final ClientConfiguration clientConfiguration;

    @Nullable private final MeterRegistry meterRegistry;
    @Nullable private StringRedisTemplate redis;

    private final CopyOnWriteArrayList<RocketMQSubscription> activeSubscriptions = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, SendCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private volatile boolean shutdown;
    @Nullable private volatile DeadLetterOperations deadLetterOps;

    public RocketMQMessageBus(MessagingProperties properties,
                               MessagePayloadCodec codec,
                               ClientServiceProvider provider) {
        this(properties, codec, provider, null);
    }

    public RocketMQMessageBus(MessagingProperties properties,
                               MessagePayloadCodec codec,
                               ClientServiceProvider provider,
                               @Nullable MeterRegistry meterRegistry) {
        this.properties = properties;
        this.codec = codec;
        this.provider = provider;
        this.meterRegistry = meterRegistry;

        validateTopicPrefix(properties.topicPrefix());

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
            byte[] encoded = validateAndEncode(message);
            org.apache.rocketmq.client.apis.message.Message rmqMsg = buildRocketMQMessage(message, encoded);
            SendReceipt receipt = producer.send(rmqMsg);
            cb.recordSuccess();
            log.debug("Message sent: topic={}, msgId={}", message.topic(), receipt.getMessageId());
            return receipt.getMessageId().toString();
        } catch (Exception e) {
            cb.recordFailure();
            throw new MessagePublishException("Failed to send message to topic: " + message.topic(), e);
        }
    }

    @Override
    public CompletableFuture<String> sendAsync(Message<?> message) {
        byte[] encoded = validateAndEncode(message);
        org.apache.rocketmq.client.apis.message.Message rmqMsg = buildRocketMQMessage(message, encoded);
        SendCircuitBreaker cb = circuitBreakerFor(message.topic());
        if (!cb.isCallAllowed()) {
            return CompletableFuture.failedFuture(
                new MessagePublishException("Circuit breaker OPEN for topic: " + message.topic()));
        }
        try {
            return producer.sendAsync(rmqMsg)
                .handle((receipt, ex) -> {
                    if (ex != null) {
                        cb.recordFailure();
                        Throwable cause = (ex instanceof CompletionException ce) ? ce.getCause() : ex;
                        throw new MessagePublishException("Async send failed: " + message.topic(), cause);
                    }
                    cb.recordSuccess();
                    return receipt.getMessageId().toString();
                });
        } catch (Exception e) {
            cb.recordFailure();
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

        MessageHandler<T> wrappedHandler = wrapWithIdempotent(handler, topic);

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

        MessageListener pushListener = messageView -> {
            try {
                T payload = codec.decode(toByteArray(messageView.getBody()), payloadType);
                Message<T> message = new Message<>(
                    messageView.getMessageId().toString(),
                    topic,
                    messageView.getTag().orElse(null),
                    payload,
                    null,
                    messageView.getKeys().stream().findFirst().orElse(null),
                    messageView.getProperties(),
                    messageView.getBornTimestamp()
                );
                handler.onMessage(message);
                log.debug("Message consumed: topic={}, group={}, msgId={}",
                    topic, group, messageView.getMessageId());
                return ConsumeResult.SUCCESS;
            } catch (PermanentConsumeException e) {
                log.error("Permanent consume error, acking to skip broker retry: topic={}, msgId={}",
                    topic, messageView.getMessageId(), e);
                return ConsumeResult.SUCCESS;
            } catch (Exception e) {
                log.error("Push consume failed: topic={}, msgId={}",
                    topic, messageView.getMessageId(), e);
                return ConsumeResult.FAILURE;
            }
        };

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
            codec, properties.shutdownTimeout(), this::sendToDeadLetter);

        ExecutorService receiveExecutor = loop.start();

        RocketMQSubscription subscription = new RocketMQSubscription(
            topic, group, null, simpleConsumer, receiveExecutor);
        subscription.setRunningFlag(loop.runningFlag());
        subscription.setCloseTimeoutMsHolder(loop.closeTimeoutMsHolder());
        return subscription;
    }

    // ==================== Dead Letter ====================

    boolean sendToDeadLetter(MessageView messageView, String topic, String group) {
        String msgId = messageView.getMessageId().toString();
        try {
            String dlqTopic = "%DLQ%" + group;
            org.apache.rocketmq.client.apis.message.Message dlqMsg =
                provider.newMessageBuilder()
                    .setTopic(dlqTopic)
                    .setBody(toByteArray(messageView.getBody()))
                    .setKeys(msgId)
                    .addProperty("originalTopic", topic)
                    .addProperty("originalGroup", group)
                    .addProperty("deadAt", Instant.now().toString())
                    .build();
            producer.send(dlqMsg);
            log.warn("Message forwarded to DLQ: dlqTopic={}, originalTopic={}, msgId={}",
                dlqTopic, topic, msgId);
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
    public void shutdown() {
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

    // ==================== Idempotent Wrapper ====================

    <T> MessageHandler<T> wrapWithIdempotent(MessageHandler<T> handler, String topic) {
        if (!properties.idempotent().enabled() || redis == null) {
            return handler;
        }
        return msg -> {
            String idempotentKey = msg.deduplicationKey();
            if (idempotentKey == null || idempotentKey.isEmpty()) {
                handler.onMessage(msg);
                return;
            }
            String redisKey = "messaging:idempotent:" + topic + ":" + idempotentKey;
            boolean marked = false;
            try {
                Long isDuplicate = redis.execute(
                    IDEMPOTENT_MARK,
                    List.of(redisKey),
                    String.valueOf(properties.idempotent().ttlSeconds()));
                if (isDuplicate != null && isDuplicate == 1L) {
                    log.info("Duplicate message skipped: topic={}, key={}", topic, idempotentKey);
                    return;
                }
                marked = true;
                handler.onMessage(msg);
            } catch (Exception e) {
                if (marked) {
                    try { redis.delete(redisKey); } catch (Exception de) { /* ignore */ }
                    throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
                }
                log.warn("Idempotent check failed (Redis unavailable), delegating to business-layer: topic={}",
                    topic, e);
                if (meterRegistry != null) {
                    meterRegistry.counter("messaging.idempotent.degraded", "topic", topic).increment();
                }
                try {
                    handler.onMessage(msg);
                } catch (Exception listenerEx) {
                    log.error("Listener failed during Redis-degraded path: topic={}, key={}",
                        topic, idempotentKey, listenerEx);
                    throw listenerEx;
                }
            }
        };
    }

    // ==================== Management ====================

    @Override
    public boolean isProducerHealthy() {
        return !shutdown && producer != null;
    }

    @Override
    public int activeSubscriptionCount() {
        return (int) activeSubscriptions.stream().filter(RocketMQSubscription::isActive).count();
    }

    @Override
    public String circuitBreakerState() {
        if (circuitBreakers.isEmpty()) {
            return "CLOSED";
        }
        StringBuilder sb = new StringBuilder();
        circuitBreakers.forEach((topic, cb) -> {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(topic).append("=").append(cb.state().name().toLowerCase());
        });
        return sb.toString();
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

        return builder.build();
    }

    private boolean isOrderedTopic(String topic) {
        return properties.orderedTopics() != null
            && properties.orderedTopics().contains(topic);
    }

    private byte[] validateAndEncode(Message<?> message) {
        String fullTopic = properties.topicPrefix() + message.topic();
        if (fullTopic.length() > 128) {
            throw new IllegalArgumentException(
                "Full topic name too long: '" + fullTopic
                + "' (prefix + topic = " + fullTopic.length() + " chars, max 128)");
        }
        if (!TOPIC_PATTERN.matcher(message.topic()).matches()) {
            throw new IllegalArgumentException(
                "Invalid topic name: '" + message.topic()
                + "'. Must be 1-128 chars, alphanumeric/underscore/hyphen/percent only.");
        }
        if (message.tag() != null && !TAG_PATTERN.matcher(message.tag()).matches()) {
            throw new IllegalArgumentException(
                "Invalid tag name: '" + message.tag()
                + "'. Must be 1-64 chars, alphanumeric/underscore/hyphen only.");
        }
        byte[] encoded = codec.encode(message.payload());
        if (encoded.length > properties.rocketmq().maxMessageSize()) {
            throw new IllegalArgumentException(
                "Message payload too large: " + encoded.length + " bytes");
        }
        return encoded;
    }

    private static void validateTopicPrefix(String prefix) {
        if (prefix != null && !prefix.isEmpty()
            && !Pattern.matches("^[a-zA-Z0-9_-][%a-zA-Z0-9_-]*$", prefix)) {
            throw new IllegalArgumentException(
                "Invalid topicPrefix: '" + prefix
                + "'. Must start with alphanumeric/underscore/hyphen, "
                + "followed by alphanumeric/underscore/hyphen/percent characters only.");
        }
    }

    private static byte[] toByteArray(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
