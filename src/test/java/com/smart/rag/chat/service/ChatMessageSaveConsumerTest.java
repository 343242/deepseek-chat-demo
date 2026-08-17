package com.smart.rag.chat.service;

import com.smart.rag.infrastructure.messaging.ConsumerConfig;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessageHandler;
import com.smart.rag.infrastructure.messaging.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatMessageSaveConsumer 测试 — 验证订阅 {@code chat_message_save} 并转发到
 * {@link ChatConversationHelper#saveMessagesAndNotify}（Phase C Step 2）。
 */
@ExtendWith(MockitoExtension.class)
class ChatMessageSaveConsumerTest {

    @Mock
    private MessageBus messageBus;

    @Mock
    private ChatConversationHelper conversationHelper;

    private ChatMessageSaveConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ChatMessageSaveConsumer(messageBus, conversationHelper);
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("start 订阅 chat_message_save，消费组 save-group，payload 类型 ChatMessagePayload")
        void startSubscribes() {
            when(messageBus.subscribe(any(), any(), any(), any(), any()))
                    .thenReturn(mock(Subscription.class));

            consumer.start();

            verify(messageBus).subscribe(
                    eq("chat_message_save"),
                    eq("save-group"),
                    any(),
                    eq(ChatMessagePayload.class),
                    any());
            assertThat(consumer.isRunning()).isTrue();
        }

        @Test
        @DisplayName("使用 ConsumerConfig.DEFAULT（PushConsumer 模式）")
        void usesDefaultPushConfig() {
            when(messageBus.subscribe(any(), any(), any(), any(), any()))
                    .thenReturn(mock(Subscription.class));

            consumer.start();

            ArgumentCaptor<ConsumerConfig> configCaptor =
                    ArgumentCaptor.forClass(ConsumerConfig.class);
            verify(messageBus).subscribe(any(), any(), configCaptor.capture(), any(), any());
            assertThat(configCaptor.getValue()).isEqualTo(ConsumerConfig.DEFAULT);
        }

        @Test
        @DisplayName("stop 关闭订阅")
        void stopClosesSubscription() {
            Subscription subscription = mock(Subscription.class);
            when(messageBus.subscribe(any(), any(), any(), any(), any()))
                    .thenReturn(subscription);

            consumer.start();
            consumer.stop();

            verify(subscription).close();
            assertThat(consumer.isRunning()).isFalse();
        }

        @Test
        @DisplayName("start 幂等")
        void startIdempotent() {
            when(messageBus.subscribe(any(), any(), any(), any(), any()))
                    .thenReturn(mock(Subscription.class));

            consumer.start();
            consumer.start();

            verify(messageBus).subscribe(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Handler")
    class Handler {

        @Test
        @DisplayName("收到 ChatMessagePayload 后转发到 saveMessagesAndNotify（含 durationMs 与未知 token=null）")
        void handlerForwardsToHelper() {
            consumer.start();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<MessageHandler<ChatMessagePayload>> handlerCaptor =
                    ArgumentCaptor.forClass(MessageHandler.class);
            verify(messageBus).subscribe(any(), any(), any(), eq(ChatMessagePayload.class), handlerCaptor.capture());

            ChatMessagePayload payload = new ChatMessagePayload(
                    "conv-1", "hello", "Hi there!", "candidate-a", 150L, 200L);
            MessageEnvelope<ChatMessagePayload> msg = new MessageEnvelope<>(
                    null, "chat_message_save", null, payload,
                    null, "dedup-key", Map.of(), System.currentTimeMillis());
            handlerCaptor.getValue().onMessage(msg);

            verify(conversationHelper).saveMessagesAndNotify(
                    "conv-1", "hello", "Hi there!", "candidate-a", 150, 200L);
        }

        @Test
        @DisplayName("saveMessagesAndNotify 抛异常时从 handler 传播出去（触发 broker 重试，不静默吞咽）")
        void handlerPropagatesPersistenceException() {
            consumer.start();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<MessageHandler<ChatMessagePayload>> handlerCaptor =
                    ArgumentCaptor.forClass(MessageHandler.class);
            verify(messageBus).subscribe(any(), any(), any(), eq(ChatMessagePayload.class), handlerCaptor.capture());

            ChatMessagePayload payload = new ChatMessagePayload(
                    "conv-1", "hello", "Hi there!", "candidate-a", null, 200L);
            MessageEnvelope<ChatMessagePayload> msg = new MessageEnvelope<>(
                    null, "chat_message_save", null, payload,
                    null, "dedup-key", Map.of(), System.currentTimeMillis());

            RuntimeException ex = new RuntimeException("boom");
            doThrow(ex).when(conversationHelper).saveMessagesAndNotify(
                    "conv-1", "hello", "Hi there!", "candidate-a", null, 200L);

            assertThatThrownBy(() -> handlerCaptor.getValue().onMessage(msg))
                    .isSameAs(ex);
        }
    }
}
