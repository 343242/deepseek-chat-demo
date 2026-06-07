package com.smart.rag.rag.etl;

import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.Subscription;
import com.smart.rag.rag.event.EtlCompletedEvent;
import com.smart.rag.rag.service.EtlDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EtlDocumentConsumerTest {

    @Mock private MessageBus messageBus;
    @Mock private EtlDispatchService etlDispatchService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Captor private ArgumentCaptor<List<EtlCandidate>> candidatesCaptor;

    private EtlDocumentConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new EtlDocumentConsumer(messageBus, etlDispatchService, eventPublisher);
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("start subscribes to rag_index_document with SimpleConsumer config")
        void startSubscribes() {
            when(messageBus.subscribe(any(), any(), any(), any(), any()))
                .thenReturn(mock(Subscription.class));

            consumer.start();

            verify(messageBus).subscribe(
                eq("rag_index_document"),
                eq("index-group"),
                any(),
                eq(EtlCandidate.class),
                any());
            assertThat(consumer.isRunning()).isTrue();
        }

        @Test
        @DisplayName("stop closes subscription")
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
        @DisplayName("start is idempotent")
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
        @DisplayName("successful ETL dispatches and publishes EtlCompletedEvent")
        void handlerSuccess() {
            EtlCandidate candidate = new EtlCandidate(1L, "bucket", "key.pdf",
                "test.pdf", "application/pdf", 1024, 42L, null);

            when(etlDispatchService.dispatch(any()))
                .thenReturn(List.of(EtlResult.success(1L, 10)));

            // Simulate handler invocation directly
            consumer.start();

            ArgumentCaptor<com.smart.rag.infrastructure.messaging.MessageHandler<EtlCandidate>>
                handlerCaptor = ArgumentCaptor.forClass(com.smart.rag.infrastructure.messaging.MessageHandler.class);
            verify(messageBus).subscribe(any(), any(), any(), eq(EtlCandidate.class), handlerCaptor.capture());

            var msg = new com.smart.rag.infrastructure.messaging.MessageEnvelope<>(
                null, "rag_index_document", null, candidate,
                "1", "1", java.util.Map.of(), System.currentTimeMillis());
            handlerCaptor.getValue().onMessage(msg);

            verify(etlDispatchService).dispatch(candidatesCaptor.capture());
            assertThat(candidatesCaptor.getValue()).hasSize(1);
            assertThat(candidatesCaptor.getValue().getFirst().documentId()).isEqualTo(1L);

            verify(eventPublisher).publishEvent(any(EtlCompletedEvent.class));
        }

        @Test
        @DisplayName("failed ETL does not publish EtlCompletedEvent")
        void handlerFailure() {
            EtlCandidate candidate = new EtlCandidate(2L, "bucket", "fail.pdf",
                "fail.pdf", "application/pdf", 512, 42L, null);

            when(etlDispatchService.dispatch(any()))
                .thenReturn(List.of(EtlResult.failed(2L, "Parse error")));

            consumer.start();

            ArgumentCaptor<com.smart.rag.infrastructure.messaging.MessageHandler<EtlCandidate>>
                handlerCaptor = ArgumentCaptor.forClass(com.smart.rag.infrastructure.messaging.MessageHandler.class);
            verify(messageBus).subscribe(any(), any(), any(), eq(EtlCandidate.class), handlerCaptor.capture());

            var msg = new com.smart.rag.infrastructure.messaging.MessageEnvelope<>(
                null, "rag_index_document", null, candidate,
                "2", "2", java.util.Map.of(), System.currentTimeMillis());
            handlerCaptor.getValue().onMessage(msg);

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("dispatch exception propagates to caller (message bus retry handles it)")
        void handlerExceptionPropagates() {
            EtlCandidate candidate = new EtlCandidate(3L, "bucket", "err.pdf",
                "err.pdf", "application/pdf", 256, 42L, null);

            when(etlDispatchService.dispatch(any()))
                .thenThrow(new RuntimeException("ETL processing failed"));

            consumer.start();

            ArgumentCaptor<com.smart.rag.infrastructure.messaging.MessageHandler<EtlCandidate>>
                handlerCaptor = ArgumentCaptor.forClass(com.smart.rag.infrastructure.messaging.MessageHandler.class);
            verify(messageBus).subscribe(any(), any(), any(), eq(EtlCandidate.class), handlerCaptor.capture());

            var msg = new com.smart.rag.infrastructure.messaging.MessageEnvelope<>(
                null, "rag_index_document", null, candidate,
                "3", "3", java.util.Map.of(), System.currentTimeMillis());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> handlerCaptor.getValue().onMessage(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("ETL processing failed");

            verifyNoInteractions(eventPublisher);
        }
    }
}
