package com.demo.chat.rag.etl;

import com.demo.chat.rag.config.EtlFastTrackProperties;
import com.demo.chat.rag.entity.RagDocument;
import com.demo.chat.rag.mapper.RagDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
 *   <li>异步提交 Transform + Load 到线程池</li>
 *   <li>异步完成后删除 BM25 原文行，替换为精确分块</li>
 * </ol>
 */
@Component
public class FastTrackStrategy implements EtlRouteStrategy {

    private static final Logger log = LoggerFactory.getLogger(FastTrackStrategy.class);

    private final Extractor extractor;
    private final Transformer transformer;
    private final Loader loader;
    private final RagDocumentMapper ragDocumentMapper;
    private final TransactionTemplate transactionTemplate;
    private final EtlTaskExecutorBridge executorBridge;
    private final EtlFastTrackProperties fastTrackProperties;
    private final JdbcTemplate jdbcTemplate;

    public FastTrackStrategy(Extractor extractor,
                             Transformer transformer,
                             Loader loader,
                             RagDocumentMapper ragDocumentMapper,
                             TransactionTemplate transactionTemplate,
                             EtlTaskExecutorBridge executorBridge,
                             EtlFastTrackProperties fastTrackProperties,
                             JdbcTemplate jdbcTemplate) {
        this.extractor = extractor;
        this.transformer = transformer;
        this.loader = loader;
        this.ragDocumentMapper = ragDocumentMapper;
        this.transactionTemplate = transactionTemplate;
        this.executorBridge = executorBridge;
        this.fastTrackProperties = fastTrackProperties;
        this.jdbcTemplate = jdbcTemplate;
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
                completeDocument(c.documentId(), 0); // chunkCount 暂为 0，异步完成后更新

                results.add(EtlResult.success(c.documentId(), 0));

                // === 阶段 3: 异步 Transform + Load ===
                asyncVectorize(c, docs);

            } catch (Exception e) {
                log.error("FastTrack BM25 write failed: id={}", c.documentId(), e);
                failDocument(c.documentId(), e);
                results.add(EtlResult.failed(c.documentId(), e.getMessage()));
            }
        }

        return results;
    }

    // ==================== BM25 快速写入 ====================

    /**
     * 将原文直接写入 vector_store 表，content_tsv 由触发器自动填充。
     * embedding 设为 NULL（无向量），BM25 检索仍可通过 content_tsv 命中。
     */
    private void writeBm25Row(Long documentId, String content) {
        jdbcTemplate.update("""
                INSERT INTO vector_store (id, content, metadata, embedding)
                VALUES (gen_random_uuid(), ?, '{"documentId": "' || ? || '", "fastTrack": true}'::json, NULL)
                """, content, String.valueOf(documentId));
        log.debug("BM25 row written for documentId={}", documentId);
    }

    // ==================== 异步向量化 ====================

    /**
     * 异步执行 Transform + Load，完成后清理 BM25 原文行。
     * <p>
     * 失败时标记 VECTOR_FAILED（BM25 仍可用）。
     */
    private void asyncVectorize(EtlCandidate c, List<Document> docs) {
        CompletableFuture
                .supplyAsync(() -> {
                    // CPU 池：Transform
                    try {
                        return executorBridge.submitCpu(() -> {
                            List<Document> chunks = transformer.transform(docs, c.fileName());
                            String docIdStr = String.valueOf(c.documentId());
                            for (Document chunk : chunks) {
                                chunk.getMetadata().put("documentId", docIdStr);
                            }
                            return chunks;
                        }).join();
                    } catch (Exception e) {
                        throw new RuntimeException("Transform failed: " + c.documentId(), e);
                    }
                })
                .thenAcceptAsync(chunks -> {
                    // IO 池：Load
                    try {
                        loader.load(chunks);
                        // 删除 BM25 原文行
                        deleteBm25Rows(c.documentId());
                        // 更新 chunkCount
                        updateChunkCount(c.documentId(), chunks.size());
                        log.info("FastTrack async completed: id={}, chunks={}", c.documentId(), chunks.size());
                    } catch (Exception e) {
                        throw new RuntimeException("Load failed: " + c.documentId(), e);
                    }
                })
                .exceptionally(ex -> {
                    log.error("FastTrack async vectorize failed: id={}, BM25 still available", c.documentId(), ex);
                    markVectorFailed(c.documentId(), ex);
                    return null;
                });
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
        List<CompletableFuture<AbstractMap.SimpleEntry<Long, List<Document>>>> futures = candidates.stream()
                .map(c -> executorBridge.submitIo(() -> {
                    try {
                        updateStatus(c.documentId(), "PARSING");
                        List<Document> docs = extractor.extract(c.bucket(), c.objectKey(), c.mimeType());
                        return new AbstractMap.SimpleEntry<>(c.documentId(), docs);
                    } catch (Exception e) {
                        log.error("FastTrack extract failed: id={}, file={}", c.documentId(), c.fileName(), e);
                        failDocument(c.documentId(), e);
                        return new AbstractMap.SimpleEntry<>(c.documentId(), (List<Document>) null);
                    }
                }))
                .toList();

        executorBridge.awaitAll(futures);

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey, AbstractMap.SimpleEntry::getValue));
    }

    // ==================== 状态管理 ====================

    private void updateStatus(Long documentId, String status) {
        transactionTemplate.executeWithoutResult(ts -> {
            RagDocument update = new RagDocument();
            update.setId(documentId);
            update.setStatus(status);
            update.setUpdateTime(LocalDateTime.now());
            ragDocumentMapper.updateById(update);
        });
    }

    private void completeDocument(Long documentId, int chunkCount) {
        transactionTemplate.executeWithoutResult(ts -> {
            RagDocument doc = new RagDocument();
            doc.setId(documentId);
            doc.setStatus("COMPLETED");
            doc.setChunkCount(chunkCount);
            doc.setUpdateTime(LocalDateTime.now());
            ragDocumentMapper.updateById(doc);
        });
    }

    private void updateChunkCount(Long documentId, int chunkCount) {
        transactionTemplate.executeWithoutResult(ts -> {
            RagDocument doc = new RagDocument();
            doc.setId(documentId);
            doc.setChunkCount(chunkCount);
            doc.setUpdateTime(LocalDateTime.now());
            ragDocumentMapper.updateById(doc);
        });
    }

    private void markVectorFailed(Throwable ex, Long documentId) {
        // unused signature, correct one below
    }

    private void markVectorFailed(Long documentId, Throwable ex) {
        log.error("Vectorization failed (BM25 still available): id={}", documentId, ex);
        try {
            transactionTemplate.executeWithoutResult(ts -> {
                RagDocument doc = ragDocumentMapper.selectById(documentId);
                if (doc != null) {
                    doc.setStatus("VECTOR_FAILED");
                    doc.setErrorMessage(truncate("Async vectorize failed: " + ex.getMessage(), 2000));
                    doc.setUpdateTime(LocalDateTime.now());
                    ragDocumentMapper.updateById(doc);
                }
            });
        } catch (Exception txEx) {
            log.error("Failed to persist VECTOR_FAILED status: id={}", documentId, txEx);
        }
    }

    private void failDocument(Long documentId, Exception e) {
        log.error("ETL failed for document: id={}", documentId, e);
        try {
            transactionTemplate.executeWithoutResult(ts -> {
                RagDocument doc = ragDocumentMapper.selectById(documentId);
                if (doc != null) {
                    doc.setStatus("FAILED");
                    doc.setErrorMessage(truncate(e.getMessage(), 2000));
                    doc.setUpdateTime(LocalDateTime.now());
                    ragDocumentMapper.updateById(doc);
                }
            });
        } catch (Exception txEx) {
            log.error("Failed to persist FAILED status: id={}", documentId, txEx);
        }
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() <= maxLen ? str : str.substring(0, maxLen);
    }
}
