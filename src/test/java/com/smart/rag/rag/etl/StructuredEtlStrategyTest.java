package com.smart.rag.rag.etl;

import com.smart.rag.infrastructure.concurrent.*;
import com.smart.rag.rag.config.EtlFastTrackProperties;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Structured ETL strategies")
class StructuredEtlStrategyTest {

    @Test
    @DisplayName("StandardStrategy opens scoped tasks for extract transform and load")
    void standardStrategy_opensScopedTasksForEachBatchStage() {
        Extractor extractor = mock(Extractor.class);
        Transformer transformer = mock(Transformer.class);
        Loader loader = mock(Loader.class);
        EtlStatusManager statusManager = mock(EtlStatusManager.class);
        RecordingScopedTasks scopedTasks = new RecordingScopedTasks();
        ThreadPoolTaskExecutor ioExecutor = executor("test-io-");
        ThreadPoolTaskExecutor cpuExecutor = executor("test-cpu-");
        EtlCandidate candidate = candidate(1L);
        Document document = new Document("raw");
        Document chunk = new Document("chunk");

        when(extractor.extract("bucket", "object-1", "text/plain")).thenReturn(List.of(document));
        when(transformer.transform(List.of(document), "file-1.txt")).thenReturn(List.of(chunk));

        try {
            StandardStrategy strategy = new StandardStrategy(
                    extractor, transformer, loader, statusManager, ioExecutor, cpuExecutor, scopedTasks);

            List<EtlResult> results = strategy.execute(List.of(candidate));

            assertThat(results).containsExactly(EtlResult.success(1L, 1));
            assertThat(scopedTasks.scopeNames())
                    .containsExactly("standard-extract", "standard-transform", "standard-load");
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
        ThreadPoolTaskExecutor ioExecutor = executor("test-io-");
        ThreadPoolTaskExecutor cpuExecutor = executor("test-cpu-");
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
                    ioExecutor,
                    cpuExecutor,
                    scopedTasks
            );

            List<EtlResult> results = strategy.execute(List.of(candidate));
            strategy.awaitAsyncCompletion();

            assertThat(results).containsExactly(EtlResult.success(2L, 0));
            verify(vectorStoreMapper).insertFastTrackRow(2L, "raw", 10L, null);
            verify(vectorStoreMapper).deleteFastTrackRows(2L);
            assertThat(scopedTasks.scopeNames())
                    .containsExactly("fast-track-extract", "fast-track-vectorize");
        } finally {
            ioExecutor.shutdown();
            cpuExecutor.shutdown();
        }
    }

    private static ThreadPoolTaskExecutor executor(String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix(prefix);
        executor.initialize();
        return executor;
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
