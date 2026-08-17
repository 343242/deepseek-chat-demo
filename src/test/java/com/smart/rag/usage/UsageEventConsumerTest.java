package com.smart.rag.usage;

import com.smart.rag.infrastructure.messaging.ConsumerConfig;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.infrastructure.messaging.MessageHandler;
import com.smart.rag.infrastructure.messaging.Subscription;
import com.smart.rag.usage.service.UsageEventService;
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
 * UsageEventConsumer 测试 — 验证订阅 {@code usage_event_record} 并转发到 UsageEventService。
 */
@ExtendWith(MockitoExtension.class)
class UsageEventConsumerTest {

    @Mock
    private MessageBus messageBus;

    @Mock
    private UsageEventService usageEventService;

    private UsageEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new UsageEventConsumer(messageBus, usageEventService);
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("start 订阅 usage_event_record，消费组 usage-group，payload 类型 UsageEventPayload")
        void startSubscribes() {
            when(messageBus.subscribe(any(), any(), any(), any(), any()))
                    .thenReturn(mock(Subscription.class));

            consumer.start();

            verify(messageBus).subscribe(
                    eq("usage_event_record"),
                    eq("usage-group"),
                    any(),
                    eq(UsageEventPayload.class),
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
        @DisplayName("收到 UsageEventPayload 后转发到 UsageEventService.record")
        void handlerForwardsToService() {
            consumer.start();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<MessageHandler<UsageEventPayload>> handlerCaptor =
                    ArgumentCaptor.forClass(MessageHandler.class);
            verify(messageBus).subscribe(any(), any(), any(), eq(UsageEventPayload.class), handlerCaptor.capture());

            UsageEventPayload payload = new UsageEventPayload(
                    "event-1", 7L, "CHAT", "conv-1", "candidate-a",
                    100L, 50L, 150L, false, true, 200L);
            MessageEnvelope<UsageEventPayload> msg = new MessageEnvelope<>(
                    null, "usage_event_record", null, payload,
                    null, "event-1", Map.of(), System.currentTimeMillis());
            handlerCaptor.getValue().onMessage(msg);

            verify(usageEventService).record(payload);
        }

        @Test
        @DisplayName("record 抛异常时从 handler 传播出去（触发 broker 重试，不静默吞咽）")
        void handlerPropagatesPersistenceException() {
            consumer.start();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<MessageHandler<UsageEventPayload>> handlerCaptor =
                    ArgumentCaptor.forClass(MessageHandler.class);
            verify(messageBus).subscribe(any(), any(), any(), eq(UsageEventPayload.class), handlerCaptor.capture());

            UsageEventPayload payload = new UsageEventPayload(
                    "event-1", 7L, "CHAT", "conv-1", "candidate-a",
                    100L, 50L, 150L, false, true, 200L);
            MessageEnvelope<UsageEventPayload> msg = new MessageEnvelope<>(
                    null, "usage_event_record", null, payload,
                    null, "event-1", Map.of(), System.currentTimeMillis());

            RuntimeException ex = new RuntimeException("boom");
            doThrow(ex).when(usageEventService).record(payload);

            assertThatThrownBy(() -> handlerCaptor.getValue().onMessage(msg))
                    .isSameAs(ex);
        }
    }
}
