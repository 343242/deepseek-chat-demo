package com.demo.chat.rag.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 标准并发策略 — IO池 Extract → CPU池 Transform → IO池 Load
 * <p>
 * 适用于大批量文档场景。三个阶段依次执行，每个阶段内部并行处理所有文档。
 * 单文档场景下行为与原始同步流程一致。
 * <p>
 * 线程安全：每个文档的状态更新使用独立 TransactionTemplate 事务，无共享可变状态。
 */
@Component
public class StandardStrategy implements EtlRouteStrategy {

    private static final Logger log = LoggerFactory.getLogger(StandardStrategy.class);

    private final Extractor extractor;
    private final Transformer transformer;
    private final Loader loader;
    private final EtlStatusManager statusManager;
    private final ThreadPoolTaskExecutor ioExecutor;
    private final ThreadPoolTaskExecutor cpuExecutor;

    public StandardStrategy(Extractor extractor,
                            Transformer transformer,
                            Loader loader,
                            EtlStatusManager statusManager,
                            ThreadPoolTaskExecutor etlIoExecutor,
                            ThreadPoolTaskExecutor etlCpuExecutor) {
        this.extractor = extractor;
        this.transformer = transformer;
        this.loader = loader;
        this.statusManager = statusManager;
        this.ioExecutor = etlIoExecutor;
        this.cpuExecutor = etlCpuExecutor;
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE; // 兜底策略，最低优先级
    }

    @Override
    public boolean shouldApply(List<EtlCandidate> candidates) {
        return true; // 总是可用（兜底）
    }

    @Override
    public List<EtlResult> execute(List<EtlCandidate> candidates) {
        log.info("Standard strategy: processing {} documents concurrently", candidates.size());

        // === 阶段 1: Extract（IO 池并行） ===
        Map<Long, List<Document>> extractedMap = extractAll(candidates);

        // === 阶段 2: Transform（CPU 池并行） ===
        Map<Long, List<Document>> chunkMap = transformAll(candidates, extractedMap);

        // === 阶段 3: Load（IO 池并行） ===
        Map<Long, Integer> loadResultMap = loadAll(candidates, chunkMap);

        // === 汇总结果 ===
        return candidates.stream()
                .map(c -> buildResult(c, extractedMap, chunkMap, loadResultMap))
                .collect(Collectors.toList());
    }

    // ==================== Extract ====================

    private Map<Long, List<Document>> extractAll(List<EtlCandidate> candidates) {
        List<CompletableFuture<ExtractOutput>> futures = candidates.stream()
                .map(c -> CompletableFuture.supplyAsync(() -> {
                    try {
                        statusManager.updateStatus(c.documentId(), EtlStatus.PARSING);
                        List<Document> docs = extractor.extract(c.bucket(), c.objectKey(), c.mimeType());
                        return new ExtractOutput(c.documentId(), docs, null);
                    } catch (Exception e) {
                        log.error("Extract failed: id={}, file={}", c.documentId(), c.fileName(), e);
                        statusManager.failDocument(c.documentId(), e);
                        return new ExtractOutput(c.documentId(), List.of(), e);
                    }
                }, ioExecutor))
                .toList();

        joinAll(futures);

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(o -> o.error == null)
                .collect(Collectors.toMap(ExtractOutput::documentId, ExtractOutput::documents));
    }

    // ==================== Transform ====================

    private Map<Long, List<Document>> transformAll(List<EtlCandidate> candidates,
                                                    Map<Long, List<Document>> extractedMap) {
        List<CompletableFuture<TransformOutput>> futures = candidates.stream()
                .filter(c -> extractedMap.containsKey(c.documentId()))
                .map(c -> CompletableFuture.supplyAsync(() -> {
                    try {
                        statusManager.updateStatus(c.documentId(), EtlStatus.CHUNKING);
                        List<Document> chunks = transformer.transform(extractedMap.get(c.documentId()), c.fileName());
                        String docIdStr = String.valueOf(c.documentId());
                        for (Document chunk : chunks) {
                            chunk.getMetadata().put("documentId", docIdStr);
                        }
                        return new TransformOutput(c.documentId(), chunks, null);
                    } catch (Exception e) {
                        log.error("Transform failed: id={}, file={}", c.documentId(), c.fileName(), e);
                        statusManager.failDocument(c.documentId(), e);
                        return new TransformOutput(c.documentId(), List.of(), e);
                    }
                }, cpuExecutor))
                .toList();

        joinAll(futures);

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(o -> o.error == null)
                .collect(Collectors.toMap(TransformOutput::documentId, TransformOutput::chunks));
    }

    // ==================== Load ====================

    private Map<Long, Integer> loadAll(List<EtlCandidate> candidates,
                                        Map<Long, List<Document>> chunkMap) {
        List<CompletableFuture<LoadOutput>> futures = candidates.stream()
                .filter(c -> chunkMap.containsKey(c.documentId()))
                .map(c -> CompletableFuture.supplyAsync(() -> {
                    try {
                        statusManager.updateStatus(c.documentId(), EtlStatus.VECTORIZING);
                        List<Document> chunks = chunkMap.get(c.documentId());
                        loader.load(chunks);
                        statusManager.completeDocument(c.documentId(), chunks.size());
                        return new LoadOutput(c.documentId(), chunks.size(), null);
                    } catch (Exception e) {
                        log.error("Load failed: id={}, file={}", c.documentId(), c.fileName(), e);
                        statusManager.failDocument(c.documentId(), e);
                        return new LoadOutput(c.documentId(), 0, e);
                    }
                }, ioExecutor))
                .toList();

        joinAll(futures);

        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toMap(LoadOutput::documentId, LoadOutput::chunkCount));
    }

    // ==================== 结果汇总 ====================

    private EtlResult buildResult(EtlCandidate c,
                                   Map<Long, List<Document>> extractedMap,
                                   Map<Long, List<Document>> chunkMap,
                                   Map<Long, Integer> loadResultMap) {
        if (!extractedMap.containsKey(c.documentId())) {
            return EtlResult.failed(c.documentId(), "Extract failed");
        }
        if (!chunkMap.containsKey(c.documentId())) {
            return EtlResult.failed(c.documentId(), "Transform failed");
        }
        Integer chunkCount = loadResultMap.get(c.documentId());
        if (chunkCount == null) {
            return EtlResult.failed(c.documentId(), "Load failed");
        }
        return EtlResult.success(c.documentId(), chunkCount);
    }

    /**
     * 等待所有 Future 完成（不吞异常，异常由各阶段内部处理）
     */
    private void joinAll(List<? extends CompletableFuture<?>> futures) {
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    private record ExtractOutput(Long documentId, List<Document> documents, Exception error) {}
    private record TransformOutput(Long documentId, List<Document> chunks, Exception error) {}
    private record LoadOutput(Long documentId, int chunkCount, Exception error) {}
}
