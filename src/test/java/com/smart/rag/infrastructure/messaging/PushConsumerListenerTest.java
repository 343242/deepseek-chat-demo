package com.smart.rag.infrastructure.messaging;

import com.smart.rag.infrastructure.exception.PermanentConsumeException;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PushConsumerListenerTest {

    private MessageHandler<String> handler;
    private MessagePayloadCodec codec;
    private SimpleConsumerReceiveLoop.DeadLetterSender dlqSender;
    private MessagingMetrics metrics;
    private MessageListener messageListener;

    @BeforeEach
    void setUp() {
        handler = mock(MessageHandler.class);
        codec = mock(MessagePayloadCodec.class);
        dlqSender = mock(SimpleConsumerReceiveLoop.DeadLetterSender.class);
        metrics = mock(MessagingMetrics.class);

        PushConsumerListener<String> listener = new PushConsumerListener<>(
            "test-topic", "test-group", String.class, handler, codec,
            dlqSender, metrics, TracePropagator.NO_OP);

        messageListener = listener.create();
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
        when(mv.getDeliveryAttempt()).thenReturn(1);
        return mv;
    }

    @Nested
    @DisplayName("Successful consumption")
    class Success {

        @Test
        @DisplayName("returns SUCCESS and records metric")
        void returnsSuccess() {
            when(codec.decode(any(byte[].class), eq(String.class))).thenReturn("hello");
            MessageView mv = mockMessageView("msg-1");

            ConsumeResult result = messageListener.consume(mv);

            assertEquals(ConsumeResult.SUCCESS, result);
            verify(handler).onMessage(argThat(env -> "msg-1".equals(env.id())));
            verify(metrics).recordConsumeSuccess(eq("test-topic"), eq("test-group"),
                eq("push"), anyLong());
        }
    }

    @Nested
    @DisplayName("Permanent error")
    class PermanentError {

        @Test
        @DisplayName("DLQ success returns SUCCESS after ack")
        void dlqSuccess_returnsSuccess() {
            when(codec.decode(any(byte[].class), eq(String.class))).thenReturn("hello");
            doThrow(new PermanentConsumeException("bad format"))
                .when(handler).onMessage(any());
            when(dlqSender.send(any(MessageView.class), eq("test-topic"), eq("test-group")))
                .thenReturn(true);

            ConsumeResult result = messageListener.consume(mockMessageView("msg-2"));

            assertEquals(ConsumeResult.SUCCESS, result);
            verify(metrics, never()).recordConsumeFailure(any(), any(), any());
        }

        @Test
        @DisplayName("DLQ failure returns FAILURE for broker retry")
        void dlqFailure_returnsFailure() {
            when(codec.decode(any(byte[].class), eq(String.class))).thenReturn("hello");
            doThrow(new PermanentConsumeException("bad format"))
                .when(handler).onMessage(any());
            when(dlqSender.send(any(MessageView.class), eq("test-topic"), eq("test-group")))
                .thenReturn(false);

            ConsumeResult result = messageListener.consume(mockMessageView("msg-3"));

            assertEquals(ConsumeResult.FAILURE, result);
            verify(metrics).recordConsumeFailure("test-topic", "test-group", "push");
        }
    }

    @Nested
    @DisplayName("Retryable error")
    class RetryableError {

        @Test
        @DisplayName("returns FAILURE with retry metric")
        void regularException_returnsFailure() {
            when(codec.decode(any(byte[].class), eq(String.class))).thenReturn("hello");
            doThrow(new RuntimeException("transient")).when(handler).onMessage(any());

            ConsumeResult result = messageListener.consume(mockMessageView("msg-4"));

            assertEquals(ConsumeResult.FAILURE, result);
            verify(metrics).recordConsumeFailure("test-topic", "test-group", "push");
            verify(metrics).recordRetry(eq("test-topic"), eq("test-group"),
                eq("push"), anyString());
        }
    }
}
