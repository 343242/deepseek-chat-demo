package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.exception.MessagingException;
import com.smart.rag.infrastructure.exception.errorcode.MessagingErrorCode;
import com.smart.rag.infrastructure.messaging.MessageBus;
import com.smart.rag.infrastructure.messaging.MessageEnvelope;
import com.smart.rag.rag.etl.EtlCandidate;
import com.smart.rag.rag.etl.EtlDocumentConsumer;
import com.smart.rag.rag.etl.EtlRouteStrategyFactory;
import com.smart.rag.rag.etl.Loader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 单一投递路径（child 2 R2）：dispatchAsync 始终经 messageBus（outbox 装饰器）；线程池兜底
 * （dispatchViaThreadPool）已删除。send 仅 outbox INSERT 失败（DB 硬故障）才抛——catch 记
 * rag.etl.publish_failed 告警计数。
 */
@ExtendWith(MockitoExtension.class)
class EtlDispatchServiceImplTest {

    @Mock private EtlRouteStrategyFactory strategyFactory;
    @Mock private Loader loader;
    @Mock private MessageBus messageBus;
    @Captor private ArgumentCaptor<MessageEnvelope<EtlCandidate>> envelopeCaptor;

    private EtlCandidate testCandidate() {
        return new EtlCandidate(1L, "bucket", "obj/key.pdf",
            "test.pdf", "application/pdf", 1024, 42L, null);
    }

    private EtlDispatchServiceImpl newService() {
        return new EtlDispatchServiceImpl(
            strategyFactory, loader, null, messageBus, registryProvider(null));
    }

    /** ObjectProvider 桩：getIfAvailable 返回给定 registry（可为 null，模拟 Bean 缺失） */
    private static ObjectProvider<MeterRegistry> registryProvider(MeterRegistry registry) {
        return new ObjectProvider<>() {
            @Override public MeterRegistry getObject() {
                if (registry == null) throw new java.util.NoSuchElementException("no registry");
                return registry;
            }
            @Override public MeterRegistry getObject(Object... args) { return getObject(); }
            @Override public MeterRegistry getIfAvailable() { return registry; }
            @Override public MeterRegistry getIfUnique() { return registry; }
        };
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
    @DisplayName("dispatchAsync — outbox INSERT 失败（send 抛 MessagingException）")
    class OutboxInsertFailure {

        private void busThrows() {
            when(messageBus.send(any())).thenThrow(
                new MessagingException(MessagingErrorCode.OUTBOX_INSERT_FAILED, "db down"));
        }

        @Test
        @DisplayName("outbox INSERT 失败 → rag.etl.publish_failed +1，不向上传播（不再回退线程池，R2）")
        void outboxInsertFailureCountsAlertAndSwallows() {
            busThrows();

            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            EtlDispatchServiceImpl service = new EtlDispatchServiceImpl(
                strategyFactory, loader, null, messageBus, registryProvider(registry));

            assertThatCode(() -> service.dispatchAsync(1L, "b", "k", "f.pdf", "application/pdf", 100, 1L, null))
                .doesNotThrowAnyException();

            assertThat(registry.find("rag.etl.publish_failed").counter()).isNotNull();
            assertThat(registry.find("rag.etl.publish_failed").counter().count()).isEqualTo(1.0);
            verifyNoInteractions(strategyFactory);   // 不再走线程池 ETL
        }

        @Test
        @DisplayName("registry 缺失时不抛（仅记日志）")
        void withoutRegistryDoesNotThrow() {
            busThrows();

            assertThatCode(() -> newService().dispatchAsync(1L, "b", "k", "f.pdf", "application/pdf", 100, 1L, null))
                .doesNotThrowAnyException();
            verifyNoInteractions(strategyFactory);
        }
    }
}
