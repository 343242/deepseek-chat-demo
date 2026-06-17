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

/**
 * Phase 0 后消息总线 always-on：构造器不再接收 {@code messagingEnabled}，{@code messageBus} 必需非空。
 * 原 "messagingEnabled=false / null bus → 线程池" 路径已删除；线程池仅作为 {@link MessagingException}
 * 降级路径存在，故 dispatchViaThreadPool 行为（事件发布 / 失败不发布 / 异常吞咽）统一在 fallback 场景下验证。
 */
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

    private EtlDispatchServiceImpl newService() {
        return new EtlDispatchServiceImpl(
            strategyFactory, DIRECT_EXECUTOR, loader, eventPublisher, null, messageBus);
    }

    @Nested
    @DisplayName("dispatchAsync — messaging path")
    class MessagingPath {

        @Test
        @DisplayName("sends candidate via messageBus")
        void sendsViaMessageBus() {
            EtlCandidate candidate = testCandidate();
            newService().dispatchAsync(candidate.documentId(), candidate.bucket(), candidate.objectKey(),
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
        @DisplayName("does not touch the thread pool / strategy on send success")
        void doesNotUseThreadPool() {
            newService().dispatchAsync(1L, "b", "k", "f.pdf", "application/pdf", 100, 1L, null);

            verifyNoInteractions(strategyFactory);
        }
    }

    @Nested
    @DisplayName("dispatchAsync — MessagingException falls back to thread pool")
    class MessagingFallback {

        private void busThrows() {
            when(messageBus.send(any())).thenThrow(
                new MessagingException(MessagingErrorCode.PUBLISH_FAILED, "broker unreachable"));
        }

        @Test
        @DisplayName("bus failure falls back to thread pool and publishes EtlCompletedEvent on success")
        void fallsBackAndPublishesEvent() {
            busThrows();
            EtlRouteStrategy strategy = mock(EtlRouteStrategy.class);
            when(strategyFactory.resolve(any())).thenReturn(strategy);
            when(strategy.execute(any())).thenReturn(List.of(EtlResult.success(1L, 10)));

            assertThatCode(() -> newService().dispatchAsync(1L, "b", "k", "f.pdf", "application/pdf", 100, 1L, null))
                .doesNotThrowAnyException();

            verify(messageBus).send(any());
            verify(strategyFactory).resolve(any());
            verify(eventPublisher).publishEvent(any(EtlCompletedEvent.class));
        }

        @Test
        @DisplayName("fallback with failed ETL does not publish event")
        void fallbackFailedEtlNoEvent() {
            busThrows();
            EtlRouteStrategy strategy = mock(EtlRouteStrategy.class);
            when(strategyFactory.resolve(any())).thenReturn(strategy);
            when(strategy.execute(any())).thenReturn(List.of(EtlResult.failed(1L, "parse error")));

            newService().dispatchAsync(1L, "b", "k", "f.pdf", "application/pdf", 100, 1L, null);

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("exception during fallback dispatch is swallowed (does not propagate)")
        void dispatchExceptionSwallowed() {
            busThrows();
            EtlRouteStrategy strategy = mock(EtlRouteStrategy.class);
            when(strategyFactory.resolve(any())).thenReturn(strategy);
            when(strategy.execute(any())).thenThrow(new RuntimeException("boom"));

            assertThatCode(() -> newService().dispatchAsync(1L, "b", "k", "f.pdf", "application/pdf", 100, 1L, null))
                .doesNotThrowAnyException();
        }
    }
}
