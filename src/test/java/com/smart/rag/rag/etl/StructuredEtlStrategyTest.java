package com.smart.rag.rag.etl;

import com.smart.rag.infrastructure.concurrent.*;
import com.smart.rag.rag.config.EtlFastTrackProperties;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.event.EtlVectorizedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Structured ETL strategies")
class StructuredEtlStrategyTest {

    @Test
    @DisplayName("StandardStrategy opens scoped tasks for extract transform and load")
    void standardStrategy_opensScopedTasksForEachBatchStage() {
        Extractor extractor = mock(Extractor.class);
        Loader loader = mock(Loader.class);
        Transformer transformer = mock(Transformer.class);
        EtlStatusManager statusManager = mock(EtlStatusManager.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        RecordingScopedTasks scopedTasks = new RecordingScopedTasks();
        ExecutorService ioExecutor = executor("test-io-");
        ExecutorService cpuExecutor = executor("test-cpu-");
        EtlCandidate candidate = candidate(1L);
        Document document = new Document("raw");
        Document chunk = new Document("chunk");

        when(extractor.extract("bucket", "object-1", "text/plain")).thenReturn(List.of(document));
        when(transformer.transform(List.of(document), "file-1.txt")).thenReturn(List.of(chunk));

        try {
            StandardStrategy strategy = new StandardStrategy(
                    extractor, transformer, loader, statusManager, ioExecutor, cpuExecutor, scopedTasks, eventPublisher);

            List<EtlResult> results = strategy.execute(List.of(candidate));

            assertThat(results).containsExactly(EtlResult.success(1L, 1));
            assertThat(scopedTasks.scopeNames())
                    .containsExactly("standard-extract", "standard-transform", "standard-load");
            verify(eventPublisher).publishEvent(any(EtlVectorizedEvent.class));
        } finally {
            ioExecutor.shutdown();
            cpuExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("FastTrackStrategy opens scoped tasks for extract and async vectorize")
    void fastTrackStrategy_opensScopedTasksForExtractAndAsyncVectorize() {
        Extractor extractor = mock(Extractor.class);
        Transformer transformer = mock(Transformer.class);
        Loader loader = mock(Loader.class);
        EtlStatusManager statusManager = mock(EtlStatusManager.class);
        VectorStoreMapper vectorStoreMapper = mock(VectorStoreMapper.class);
        RecordingScopedTasks scopedTasks = new RecordingScopedTasks();
        ExecutorService ioExecutor = executor("test-io-");
        ExecutorService cpuExecutor = executor("test-cpu-");
        EtlCandidate candidate = candidate(2L);
        Document document = new Document("raw");
        Document chunk = new Document("chunk");

        when(extractor.extract("bucket", "object-2", "text/plain")).thenReturn(List.of(document));
        when(transformer.transform(List.of(document), "file-2.txt")).thenReturn(List.of(chunk));

        try {
            FastTrackStrategy strategy = new FastTrackStrategy(
                    extractor,
                    transformer,
                    loader,
                    statusManager,
                    new EtlFastTrackProperties(),
                    vectorStoreMapper,
                    mock(ApplicationEventPublisher.class),
                    ioExecutor,
                    cpuExecutor,
                    scopedTasks
            );

            List<EtlResult> results = strategy.execute(List.of(candidate));
            strategy.awaitAsyncCompletion();

            assertThat(results).containsExactly(EtlResult.success(2L, 0));
            verify(vectorStoreMapper).insertFastTrackRow(2L, "raw", 10L, null, "file-2.txt");
            verify(vectorStoreMapper).deleteFastTrackRows(2L);
            assertThat(scopedTasks.scopeNames())
                    .containsExactly("fast-track-extract", "fast-track-vectorize");
        } finally {
            ioExecutor.shutdown();
            cpuExecutor.shutdown();
        }
    }

    private static ExecutorService executor(String prefix) {
        return new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8),
                r -> {
                    Thread t = new Thread(r);
                    t.setName(prefix + t.threadId());
                    return t;
                }
        );
    }

    private static EtlCandidate candidate(Long documentId) {
        return new EtlCandidate(
                documentId,
                "bucket",
                "object-" + documentId,
                "file-" + documentId + ".txt",
                "text/plain",
                1024L,
                10L,
                null
        );
    }

    private static final class RecordingScopedTasks implements ScopedTasks {

        private final ScopedTasks delegate = new DefaultScopedTasks();
        private final List<String> scopeNames = new ArrayList<>();

        @Override
        public TaskScope open(String name) {
            scopeNames.add(name);
            return delegate.open(name);
        }

        @Override
        public TaskScope open(String name, ScopePolicy policy) {
            scopeNames.add(name);
            return delegate.open(name, policy);
        }

        @Override
        public TaskScope open(String name, ScopeOptions options) {
            scopeNames.add(name);
            return delegate.open(name, options);
        }

        @Override
        public TaskScope open(String name, ScopeOptions options, ExecutorService executor) {
            scopeNames.add(name);
            return delegate.open(name, options, executor);
        }

        private List<String> scopeNames() {
            return List.copyOf(scopeNames);
        }
    }
}
