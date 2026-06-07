package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.MessagingException;
import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.rag.etl.EtlCandidate;
import com.smart.rag.rag.etl.EtlDocumentConsumer;
import com.smart.rag.rag.etl.EtlResult;
import com.smart.rag.rag.etl.EtlRouteStrategy;
import com.smart.rag.rag.etl.EtlRouteStrategyFactory;
import com.smart.rag.rag.etl.Loader;
import com.smart.rag.rag.event.EtlCompletedEvent;
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
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EtlDispatchServiceImplTest {

    @Mock private EtlRouteStrategyFactory strategyFactory;
    @Mock private Loader loader;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private MessageBus messageBus;
    @Captor private ArgumentCaptor<MessageEnvelope<EtlCandidate>> envelopeCaptor;

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private EtlCandidate testCandidate() {
        return new EtlCandidate(1L, "bucket", "obj/key.pdf",
            "test.pdf", "application/pdf", 1024, 42L, null);
    }

    @Nested
    @DisplayName("dispatchAsync — messaging path")
    class MessagingPath {

        @Test
        @DisplayName("messagingEnabled=true sends via messageBus")
        void sendsViaMessageBus() {
            EtlDispatchServiceImpl service = new EtlDispatchServiceImpl(
                strategyFactory, DIRECT_EXECUTOR, loader, eventPublisher,
                null, messageBus, true);

            EtlCandidate candidate = testCandidate();
            service.dispatchAsync(candidate.documentId(), candidate.bucket(), candidate.objectKey(),
                candidate.fileName(), candidate.mimeType(), candidate.fileSize(),
                candidate.userId(), candidate.teamId());

            verify(messageBus).send(envelopeCaptor.capture());
            MessageEnvelope<EtlCandidate> envelope = envelopeCaptor.getValue();
            assertThat(envelope.topic()).isEqualTo(EtlDocumentConsumer.TOPIC);
            assertThat(envelope.payload()).isEqualTo(candidate);
            assertThat(envelope.hashKey()).isEqualTo("1");
            assertThat(envelope.deduplicationKey()).isEqualTo("1");
        }

        @Test
        @DisplayName("messagingEnabled=true does not use thread pool")
        void doesNotUseThreadPool() {
            EtlDispatchServiceImpl service = new EtlDispatchServiceImpl(
                strategyFactory, DIRECT_EXECUTOR, loader, eventPublisher,
                null, messageBus, true);

            service.dispatchAsync(1L, "b", "k", "f.pdf", "application/pdf", 100, 1L, null);

            verifyNoInteractions(strategyFactory);
        }

        @Test
        @DisplayName("messageBus null falls back to thread pool even if messagingEnabled=true")
        void nullMessageBusFallsBack() {
            EtlRouteStrategy strategy = mock(EtlRouteStrategy.class);
            when(strategyFactory.resolve(any())).thenReturn(strategy);
            when(strategy.execute(any())).thenReturn(List.of(EtlResult.success(1L, 5)));

            EtlDispatchServiceImpl service = new EtlDispatchServiceImpl(
                strategyFactory, DIRECT_EXECUTOR, loader, eventPublisher,
                null, null, true);

            service.dispatchAsync(1L, "b", "k", "f.pdf", "application/pdf", 100, 1L, null);

            verify(strategyFactory).resolve(any());
        }
    }

    @Nested
    @DisplayName("dispatchAsync — messaging fallback")
    class MessagingFallback {

        @Test
        @DisplayName("MessagingException falls back to thread pool")
        void messagingExceptionFallsBack() {
            when(messageBus.send(any())).thenThrow(
                new MessagingException(MessagingErrorCode.PUBLISH_FAILED, "broker unreachable"));
            EtlRouteStrategy strategy = mock(EtlRouteStrategy.class);
            when(strategyFactory.resolve(any())).thenReturn(strategy);
            when(strategy.execute(any())).thenReturn(List.of(EtlResult.success(1L, 10)));

            EtlDispatchServiceImpl service = new EtlDispatchServiceImpl(
                strategyFactory, DIRECT_EXECUTOR, loader, eventPublisher,
                null, messageBus, true);

            assertThatCode(() -> service.dispatchAsync(1L, "b", "k", "f.pdf", "application/pdf", 100, 1L, null))
                .doesNotThrowAnyException();

            verify(messageBus).send(any());
            verify(strategyFactory).resolve(any());
            verify(eventPublisher).publishEvent(any(EtlCompletedEvent.class));
        }

        @Test
        @DisplayName("MessagingException fallback with failed ETL does not publish event")
        void messagingExceptionFallbackFailedEtl() {
            when(messageBus.send(any())).thenThrow(
                new MessagingException(MessagingErrorCode.PUBLISH_FAILED, "broker unreachable"));
            EtlRouteStrategy strategy = mock(EtlRouteStrategy.class);
            when(strategyFactory.resolve(any())).thenReturn(strategy);
            when(strategy.execute(any())).thenReturn(List.of(EtlResult.failed(1L, "parse error")));

            EtlDispatchServiceImpl service = new EtlDispatchServiceImpl(
                strategyFactory, DIRECT_EXECUTOR, loader, eventPublisher,
                null, messageBus, true);

            service.dispatchAsync(1L, "b", "k", "f.pdf", "application/pdf", 100, 1L, null);

            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("dispatchAsync — thread pool path")
    class ThreadPoolPath {

        @Test
        @DisplayName("messagingEnabled=false dispatches via thread pool")
        void dispatchesViaThreadPool() {
            EtlRouteStrategy strategy = mock(EtlRouteStrategy.class);
            when(strategyFactory.resolve(any())).thenReturn(strategy);
            when(strategy.execute(any())).thenReturn(List.of(EtlResult.success(1L, 5)));

            EtlDispatchServiceImpl service = new EtlDispatchServiceImpl(
                strategyFactory, DIRECT_EXECUTOR, loader, eventPublisher,
                null, null, false);

            service.dispatchAsync(1L, "b", "k", "f.pdf", "application/pdf", 100, 1L, null);

            verify(strategyFactory).resolve(any());
            verify(strategy).execute(any());
        }

        @Test
        @DisplayName("completed ETL publishes EtlCompletedEvent")
        void completedEtlPublishesEvent() {
            EtlRouteStrategy strategy = mock(EtlRouteStrategy.class);
            when(strategyFactory.resolve(any())).thenReturn(strategy);
            when(strategy.execute(any())).thenReturn(List.of(EtlResult.success(1L, 10)));

            EtlDispatchServiceImpl service = new EtlDispatchServiceImpl(
                strategyFactory, DIRECT_EXECUTOR, loader, eventPublisher,
                null, null, false);

            service.dispatchAsync(1L, "b", "k", "f.pdf", "application/pdf", 100, 42L, 7L);

            ArgumentCaptor<EtlCompletedEvent> eventCaptor = ArgumentCaptor.forClass(EtlCompletedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().documentId()).isEqualTo(1L);
            assertThat(eventCaptor.getValue().userId()).isEqualTo(42L);
            assertThat(eventCaptor.getValue().teamId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("failed ETL does not publish event")
        void failedEtlNoEvent() {
            EtlRouteStrategy strategy = mock(EtlRouteStrategy.class);
            when(strategyFactory.resolve(any())).thenReturn(strategy);
            when(strategy.execute(any())).thenReturn(List.of(EtlResult.failed(1L, "error")));

            EtlDispatchServiceImpl service = new EtlDispatchServiceImpl(
                strategyFactory, DIRECT_EXECUTOR, loader, eventPublisher,
                null, null, false);

            service.dispatchAsync(1L, "b", "k", "f.pdf", "application/pdf", 100, 1L, null);

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("exception in dispatch does not propagate")
        void dispatchExceptionSwallowed() {
            EtlRouteStrategy strategy = mock(EtlRouteStrategy.class);
            when(strategyFactory.resolve(any())).thenReturn(strategy);
            when(strategy.execute(any())).thenThrow(new RuntimeException("boom"));

            EtlDispatchServiceImpl service = new EtlDispatchServiceImpl(
                strategyFactory, DIRECT_EXECUTOR, loader, eventPublisher,
                null, null, false);

            assertThatCode(() -> service.dispatchAsync(1L, "b", "k", "f.pdf", "application/pdf", 100, 1L, null))
                .doesNotThrowAnyException();
        }
    }
}
