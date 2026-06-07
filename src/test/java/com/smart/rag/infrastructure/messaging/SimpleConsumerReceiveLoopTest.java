package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.exception.PermanentConsumeException;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SimpleConsumerReceiveLoopTest {

    private SimpleConsumer simpleConsumer;
    private MessageHandler<String> handler;
    private MessagePayloadCodec codec;
    private SimpleConsumerReceiveLoop.DeadLetterSender dlqSender;
    private MessagingMetrics metrics;
    private ConsumerConfig config;
    private ExecutorService receiveExecutor;

    @BeforeEach
    void setUp() {
        simpleConsumer = mock(SimpleConsumer.class);
        handler = mock(MessageHandler.class);
        codec = mock(MessagePayloadCodec.class);
        dlqSender = mock(SimpleConsumerReceiveLoop.DeadLetterSender.class);
        metrics = mock(MessagingMetrics.class);

        config = ConsumerConfig.builder()
            .consumerMode(ConsumerMode.SIMPLE)
            .concurrency(2)
            .batchSize(1)
            .invisibleDuration(Duration.ofSeconds(30))
            .retryPolicy(new RetryPolicy(3))
            .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (receiveExecutor != null) {
            receiveExecutor.shutdownNow();
            receiveExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private MessageView mockMessageView(String msgId) {
        MessageView mv = mock(MessageView.class);
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn(msgId);
        when(mv.getMessageId()).thenReturn(messageId);
        when(mv.getTag()).thenReturn(Optional.empty());
        when(mv.getKeys()).thenReturn(Collections.emptyList());
        when(mv.getProperties()).thenReturn(Map.of());
        when(mv.getBornTimestamp()).thenReturn(System.currentTimeMillis());
        when(mv.getBody()).thenReturn(ByteBuffer.wrap("\"hello\"".getBytes(StandardCharsets.UTF_8)));
        return mv;
    }

    private SimpleConsumerReceiveLoop<String> createLoop() {
        return new SimpleConsumerReceiveLoop<>(
            "test-topic", "test-group", config, String.class,
            handler, simpleConsumer, codec,
            Duration.ofSeconds(10), dlqSender, metrics, TracePropagator.NO_OP);
    }

    @Nested
    @DisplayName("Successful message processing")
    class SuccessProcessing {

        @Test
        @DisplayName("invokes handler and acks on success")
        void success_invokesHandlerAndAcks() throws Exception {
            when(codec.decode(any(byte[].class), eq(String.class))).thenReturn("hello");
            MessageView mv = mockMessageView("msg-ok");
            AtomicBoolean handlerCalled = new AtomicBoolean(false);
            doAnswer(inv -> { handlerCalled.set(true); return null; })
                .when(handler).onMessage(any());

            when(simpleConsumer.receive(anyInt(), any(Duration.class)))
                .thenReturn(List.of(mv))
                .thenReturn(Collections.emptyList());

            SimpleConsumerReceiveLoop<String> loop = createLoop();
            receiveExecutor = loop.start();

            await().atMost(5, TimeUnit.SECONDS).untilTrue(handlerCalled);
            loop.runningFlag().set(false);

            verify(simpleConsumer).ack(mv);
            verify(metrics).recordConsumeSuccess(eq("test-topic"), eq("test-group"),
                eq("simple"), anyLong());
        }
    }

    @Nested
    @DisplayName("Permanent error handling")
    class PermanentError {

        @Test
        @DisplayName("forwards to DLQ and acks on permanent error")
        void permanentError_dlqAndAck() throws Exception {
            when(codec.decode(any(byte[].class), eq(String.class))).thenReturn("hello");
            MessageView mv = mockMessageView("msg-perm");
            AtomicBoolean dlqCalled = new AtomicBoolean(false);

            doThrow(new PermanentConsumeException("unrecoverable"))
                .when(handler).onMessage(any());
            doAnswer(inv -> { dlqCalled.set(true); return true; })
                .when(dlqSender).send(any(MessageView.class), eq("test-topic"), eq("test-group"));

            when(simpleConsumer.receive(anyInt(), any(Duration.class)))
                .thenReturn(List.of(mv))
                .thenReturn(Collections.emptyList());

            SimpleConsumerReceiveLoop<String> loop = createLoop();
            receiveExecutor = loop.start();

            await().atMost(5, TimeUnit.SECONDS).untilTrue(dlqCalled);
            loop.runningFlag().set(false);

            verify(dlqSender).send(mv, "test-topic", "test-group");
            verify(simpleConsumer).ack(mv);
            verify(metrics).recordConsumeFailure("test-topic", "test-group", "simple");
        }
    }

    @Nested
    @DisplayName("Retryable error handling")
    class RetryableError {

        @Test
        @DisplayName("no retry policy: forwards to DLQ immediately")
        void noRetryPolicy_dlqImmediately() throws Exception {
            ConsumerConfig noRetryConfig = ConsumerConfig.builder()
                .consumerMode(ConsumerMode.SIMPLE)
                .concurrency(2)
                .batchSize(1)
                .invisibleDuration(Duration.ofSeconds(30))
                .retryPolicy(RetryPolicy.NO_RETRY)
                .build();

            when(codec.decode(any(byte[].class), eq(String.class))).thenReturn("hello");
            MessageView mv = mockMessageView("msg-noretry");
            AtomicBoolean dlqCalled = new AtomicBoolean(false);

            doThrow(new RuntimeException("transient"))
                .when(handler).onMessage(any());
            doAnswer(inv -> { dlqCalled.set(true); return true; })
                .when(dlqSender).send(any(MessageView.class), eq("test-topic"), eq("test-group"));

            when(simpleConsumer.receive(anyInt(), any(Duration.class)))
                .thenReturn(List.of(mv))
                .thenReturn(Collections.emptyList());

            SimpleConsumerReceiveLoop<String> loop = new SimpleConsumerReceiveLoop<>(
                "test-topic", "test-group", noRetryConfig, String.class,
                handler, simpleConsumer, codec,
                Duration.ofSeconds(10), dlqSender, metrics, TracePropagator.NO_OP);
            receiveExecutor = loop.start();

            await().atMost(5, TimeUnit.SECONDS).untilTrue(dlqCalled);
            loop.runningFlag().set(false);

            verify(dlqSender).send(mv, "test-topic", "test-group");
            verify(metrics).recordConsumeFailure("test-topic", "test-group", "simple");
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("shutdownProcessingPool terminates the pool cleanly")
        void shutdownProcessingPool_terminates() throws Exception {
            when(simpleConsumer.receive(anyInt(), any(Duration.class)))
                .thenReturn(Collections.emptyList());

            SimpleConsumerReceiveLoop<String> loop = createLoop();
            receiveExecutor = loop.start();

            loop.shutdownProcessingPool();
            loop.runningFlag().set(false);

            // No exception = pool terminated cleanly within timeout
        }
    }
}
