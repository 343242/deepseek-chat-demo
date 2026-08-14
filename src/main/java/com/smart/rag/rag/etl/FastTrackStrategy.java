package com.smart.rag.rag.etl;

import com.smart.rag.infrastructure.concurrent.ExecutorMode;
import com.smart.rag.infrastructure.concurrent.ScopeJoiner;
import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.rag.config.EtlFastTrackProperties;
import com.smart.rag.rag.event.EtlVectorizedEvent;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 快速通道策略 — 小文档 BM25 先行 + 异步向量化
 * <p>
 * 适用条件：文档数量 ≤ maxDocCount 且总大小 ≤ maxTotalSize。
 * <p>
 * 执行流程：
 * <ol>
 *   <li>IO 池并行 Extract 所有文档</li>
 *   <li>同步：原文直接 INSERT 到 vector_store（content + content_tsv），BM25 立即可用</li>
 *   <li>立即返回 status=COMPLETED</li>
 *   <li>异步提交 Transform + Load（CPU池 → IO池）</li>
 *   <li>异步完成后删除 BM25 原文行，替换为精确分块</li>
 * </ol>
 */
@Component
public class FastTrackStrategy implements EtlRouteStrategy {

    private static final Logger log = LoggerFactory.getLogger(FastTrackStrategy.class);

    /** 异步向量化任务等待超时（优雅停机时最多等待时长） */
    private static final Duration ASYNC_SHUTDOWN_TIMEOUT = Duration.ofMinutes(5);

    private final Extractor extractor;
    private final Transformer transformer;
    private final Loader loader;
    private final EtlStatusManager statusManager;
    private final EtlFastTrackProperties fastTrackProperties;
    private final VectorStoreMapper vectorStoreMapper;
    private final EtlStrategyContext ctx;

    /** 追踪进行中的异步向量化任务，支持优雅停机 */
    private final Set<CompletableFuture<?>> activeAsyncTasks = ConcurrentHashMap.newKeySet();

    public FastTrackStrategy(Extractor extractor,
                             Transformer transformer,
                             Loader loader,
                             EtlStatusManager statusManager,
                             EtlFastTrackProperties fastTrackProperties,
                             VectorStoreMapper vectorStoreMapper,
                             EtlStrategyContext etlStrategyContext) {
        this.extractor = extractor;
        this.transformer = transformer;
        this.loader = loader;
        this.statusManager = statusManager;
        this.fastTrackProperties = fastTrackProperties;
        this.vectorStoreMapper = vectorStoreMapper;
        this.ctx = etlStrategyContext;
    }

    @Override
    public int getOrder() {
        return 0; // 高优先级，先于 StandardStrategy 判定
    }

    @Override
    public boolean shouldApply(List<EtlCandidate> candidates) {
        if (!fastTrackProperties.isEnabled()) {
            return false;
        }
        if (candidates.size() > fastTrackProperties.getMaxDocCount()) {
            return false;
        }
        long totalSize = candidates.stream()
                .mapToLong(EtlCandidate::fileSize)
                .sum();
        return totalSize <= fastTrackProperties.getMaxTotalSizeBytes();
    }

    @Override
    public List<EtlResult> execute(List<EtlCandidate> candidates) {
        log.info("FastTrack strategy: processing {} documents (BM25 first, async vectorize)", candidates.size());

        // === 阶段 1: IO 池并行 Extract ===
        Map<Long, List<Document>> extractedMap = extractAll(candidates);

        List<EtlResult> results = new ArrayList<>();

        for (EtlCandidate c : candidates) {
            List<Document> docs = extractedMap.get(c.documentId());
            if (docs == null || docs.isEmpty()) {
                results.add(EtlResult.failed(c.documentId(), "Extract failed"));
                continue;
            }

            // === 阶段 2: 合并原文，写入 BM25 ===
            try {
                String fullContent = docs.stream()
                        .map(Document::getText)
                        .collect(Collectors.joining("\n\n"));

                writeBm25Row(c.documentId(), fullContent, c.userId(), c.teamId(), c.fileName());
                statusManager.completeDocument(c.documentId(), 0);

                results.add(EtlResult.success(c.documentId(), 0));

                // === 阶段 3: 异步 Transform + Load ===
                // 防御性拷贝：异步任务持有独立的 docs 列表引用
                List<Document> docsCopy = new ArrayList<>(docs);
                asyncVectorize(c, docsCopy);

            } catch (Exception e) {
                log.error("FastTrack BM25 write failed: id={}", c.documentId(), e);
                statusManager.failDocument(c.documentId(), e);
                results.add(EtlResult.failed(c.documentId(),
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        return results;
    }

    /**
     * 等待所有进行中的异步向量化任务完成（用于优雅停机）。
     * <p>
     * 销毁顺序安全性：本策略直接依赖 etlIoExecutor/etlCpuExecutor 等 Bean，
     * Spring 按创建的逆序销毁——依赖方（本策略）先于被依赖方（线程池）销毁，
     * 因此本 @PreDestroy 执行时线程池仍然存活，in-flight 任务可正常跑完；
     * {@link EtlExecutorConfig#destroy()}（配置类先于线程池创建，故后销毁）随后才关闭线程池。
     */
    @PreDestroy
    public void shutdown() {
        awaitAsyncCompletion();
    }

    /**
     * 等待所有进行中的异步向量化任务完成（用于优雅停机）
     */
    public void awaitAsyncCompletion() {
        if (activeAsyncTasks.isEmpty()) return;
        log.info("Waiting for {} async vectorize tasks to complete...", activeAsyncTasks.size());
        CompletableFuture.allOf(activeAsyncTasks.toArray(CompletableFuture[]::new))
                .orTimeout(ASYNC_SHUTDOWN_TIMEOUT.toMinutes(), TimeUnit.MINUTES)
                .join();
    }

    // ==================== BM25 快速写入 ====================

    /**
     * 将原文直接写入 vector_store 表，content_tsv 由触发器自动填充。
     * embedding 设为 NULL（无向量），BM25 检索仍可通过 content_tsv 命中。
     */
    private void writeBm25Row(Long documentId, String content, Long userId, @Nullable Long teamId, String fileName) {
        vectorStoreMapper.insertFastTrackRow(documentId, content, userId, teamId, fileName);
    }

    // ==================== 异步向量化（P0 修复：直接指定 executor） ====================

    /**
     * 异步执行 Transform + Load，完成后清理 BM25 原文行。
     * <p>
     * Transform 提交到 CPU 池，Load 提交到 IO 池。
     * 失败时标记 VECTOR_FAILED（BM25 仍可用）。
     */
    private void asyncVectorize(EtlCandidate c, List<Document> docs) {
        CompletableFuture<Void> future = CompletableFuture
                .runAsync(() -> {
                    List<Document> chunks = transformChunks(c, docs);

                    loader.load(chunks);
                    eventComplete(c, chunks);
                }, ctx.ioExecutor())
                .exceptionally(ex -> {
                    log.error("FastTrack async vectorize failed: id={}, BM25 still available", c.documentId(), ex);
                    statusManager.markVectorFailed(c.documentId(), ex);
                    return null;
                });

        activeAsyncTasks.add(future);
        future.whenComplete((v, ex) -> activeAsyncTasks.remove(future));
    }

    /**
     * Transform（CPU 池 TaskScope 内执行）+ 元数据填充。
     */
    private List<Document> transformChunks(EtlCandidate c, List<Document> docs) {
        try (TaskScope scope = openExternalScope("fast-track-vectorize", ctx.cpuExecutor())) {
            scope.fork("transform-" + c.documentId(), () -> {
                List<Document> transformed = transformer.transform(docs, c.fileName());
                ChunkMetadataEnricher.enrich(transformed, c.documentId(), c.userId(), c.teamId(), c.fileName());
                return transformed;
            });
            return scope.join(ScopeJoiner.successfulResults(List.class)).stream()
                    .flatMap(List::stream)
                    .toList();
        }
    }

    /**
     * 异步收尾：发布向量化事件 + 更新分块数 + 清理 BM25 占位行。
     * <p>
     * 顺序说明：先 updateChunkCount 再 deleteFastTrackRows（非事务两步）——
     * 若两步之间失败，最坏情况是残留 BM25 占位行（BM25 可检索性不受影响，
     * 后续重建/重投递可幂等清理）；反之若先删后计数失败，chunk_count 永久丢失，
     * UI 分块数错误，危害更大。故选择「计数先行」。
     */
    private void eventComplete(EtlCandidate c, List<Document> chunks) {
        ctx.eventPublisher().publishEvent(new EtlVectorizedEvent(
                c.documentId(), c.userId(), c.teamId()));
        statusManager.updateChunkCount(c.documentId(), chunks.size());
        vectorStoreMapper.deleteFastTrackRows(c.documentId());
        log.info("FastTrack async completed: id={}, chunks={}", c.documentId(), chunks.size());
    }

    // ==================== Extract ====================

    private Map<Long, List<Document>> extractAll(List<EtlCandidate> candidates) {
        try (TaskScope scope = openExternalScope("fast-track-extract", ctx.ioExecutor())) {
            for (EtlCandidate c : candidates) {
                scope.fork("extract-" + c.documentId(), () -> {
                    try {
                        statusManager.updateStatus(c.documentId(), EtlStatus.PARSING);
                        List<Document> docs = extractor.extract(c.bucket(), c.objectKey(), c.mimeType());
                        return new ExtractOutput(c.documentId(), docs);
                    } catch (Exception e) {
                        log.error("FastTrack extract failed: id={}, file={}", c.documentId(), c.fileName(), e);
                        statusManager.failDocument(c.documentId(), e);
                        return new ExtractOutput(c.documentId(), null);
                    }
                });
            }

            return scope.join(ScopeJoiner.successfulResults(ExtractOutput.class)).stream()
                    .filter(o -> o.documents != null)
                    .collect(Collectors.toMap(ExtractOutput::documentId, ExtractOutput::documents));
        }
    }

    private TaskScope openExternalScope(String name, ExecutorService executor) {
        ScopeOptions options = ScopeOptions.builder(name)
                .policy(ScopePolicy.COLLECT_ALL)
                .executorMode(ExecutorMode.SHARED_EXECUTOR)
                .executorOwnedByScope(false)
                .defaultTimeout(ChunkMetadataEnricher.DEFAULT_SCOPE_TIMEOUT)
                .build();
        return ctx.scopedTasks().open(name, options, executor);
    }

    private record ExtractOutput(Long documentId, List<Document> documents) {}
}
