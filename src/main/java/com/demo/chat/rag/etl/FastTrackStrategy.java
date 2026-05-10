package com.demo.chat.rag.etl;

import com.demo.chat.rag.config.EtlFastTrackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    private final Extractor extractor;
    private final Transformer transformer;
    private final Loader loader;
    private final EtlStatusManager statusManager;
    private final EtlFastTrackProperties fastTrackProperties;
    private final JdbcTemplate jdbcTemplate;
    private final ThreadPoolTaskExecutor ioExecutor;
    private final ThreadPoolTaskExecutor cpuExecutor;

    /** 追踪进行中的异步向量化任务，支持优雅停机 */
    private final Set<CompletableFuture<?>> activeAsyncTasks = ConcurrentHashMap.newKeySet();

    public FastTrackStrategy(Extractor extractor,
                             Transformer transformer,
                             Loader loader,
                             EtlStatusManager statusManager,
                             EtlFastTrackProperties fastTrackProperties,
                             JdbcTemplate jdbcTemplate,
                             ThreadPoolTaskExecutor etlIoExecutor,
                             ThreadPoolTaskExecutor etlCpuExecutor) {
        this.extractor = extractor;
        this.transformer = transformer;
        this.loader = loader;
        this.statusManager = statusManager;
        this.fastTrackProperties = fastTrackProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.ioExecutor = etlIoExecutor;
        this.cpuExecutor = etlCpuExecutor;
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

                writeBm25Row(c.documentId(), fullContent);
                statusManager.completeDocument(c.documentId(), 0);

                results.add(EtlResult.success(c.documentId(), 0));

                // === 阶段 3: 异步 Transform + Load ===
                // 防御性拷贝：异步任务持有独立的 docs 列表引用
                List<Document> docsCopy = new ArrayList<>(docs);
                asyncVectorize(c, docsCopy);

            } catch (Exception e) {
                log.error("FastTrack BM25 write failed: id={}", c.documentId(), e);
                statusManager.failDocument(c.documentId(), e);
                results.add(EtlResult.failed(c.documentId(), e.getMessage()));
            }
        }

        return results;
    }

    /**
     * 等待所有进行中的异步向量化任务完成（用于优雅停机）
     */
    public void awaitAsyncCompletion() {
        if (activeAsyncTasks.isEmpty()) return;
        log.info("Waiting for {} async vectorize tasks to complete...", activeAsyncTasks.size());
        CompletableFuture.allOf(activeAsyncTasks.toArray(CompletableFuture[]::new)).join();
    }

    // ==================== BM25 快速写入 ====================

    /**
     * 将原文直接写入 vector_store 表，content_tsv 由触发器自动填充。
     * embedding 设为 NULL（无向量），BM25 检索仍可通过 content_tsv 命中。
     */
    private void writeBm25Row(Long documentId, String content) {
        String metadataJson = "{\"documentId\": \"" + documentId + "\", \"fastTrack\": true}";
        jdbcTemplate.update("""
                INSERT INTO vector_store (id, content, metadata, embedding)
                VALUES (gen_random_uuid(), ?, ?::json, NULL)
                """, content, metadataJson);
        log.debug("BM25 row written for documentId={}", documentId);
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
                .supplyAsync(() -> {
                    // CPU 池：Transform
                    List<Document> chunks = transformer.transform(docs, c.fileName());
                    String docIdStr = String.valueOf(c.documentId());
                    for (Document chunk : chunks) {
                        chunk.getMetadata().put("documentId", docIdStr);
                    }
                    return chunks;
                }, cpuExecutor)
                .thenAcceptAsync(chunks -> {
                    // IO 池：Load
                    loader.load(chunks);
                    deleteBm25Rows(c.documentId());
                    statusManager.updateChunkCount(c.documentId(), chunks.size());
                    log.info("FastTrack async completed: id={}, chunks={}", c.documentId(), chunks.size());
                }, ioExecutor)
                .exceptionally(ex -> {
                    log.error("FastTrack async vectorize failed: id={}, BM25 still available", c.documentId(), ex);
                    statusManager.markVectorFailed(c.documentId(), ex);
                    return null;
                });

        activeAsyncTasks.add(future);
        future.whenComplete((v, ex) -> activeAsyncTasks.remove(future));
    }

    /**
     * 删除 BM25 快速写入的原文行
     */
    private void deleteBm25Rows(Long documentId) {
        jdbcTemplate.update("""
                DELETE FROM vector_store
                WHERE metadata->>'documentId' = ?
                  AND metadata->>'fastTrack' = 'true'
                """, String.valueOf(documentId));
        log.debug("BM25 fast-track rows deleted for documentId={}", documentId);
    }

    // ==================== Extract ====================

    private Map<Long, List<Document>> extractAll(List<EtlCandidate> candidates) {
        List<CompletableFuture<ExtractOutput>> futures = candidates.stream()
                .map(c -> CompletableFuture.supplyAsync(() -> {
                    try {
                        statusManager.updateStatus(c.documentId(), EtlStatus.PARSING);
                        List<Document> docs = extractor.extract(c.bucket(), c.objectKey(), c.mimeType());
                        return new ExtractOutput(c.documentId(), docs);
                    } catch (Exception e) {
                        log.error("FastTrack extract failed: id={}, file={}", c.documentId(), c.fileName(), e);
                        statusManager.failDocument(c.documentId(), e);
                        return new ExtractOutput(c.documentId(), null);
                    }
                }, ioExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(o -> o.documents != null)
                .collect(Collectors.toMap(ExtractOutput::documentId, ExtractOutput::documents));
    }

    private record ExtractOutput(Long documentId, List<Document> documents) {}
}
