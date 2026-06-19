package com.smart.rag.rag.retrieval;

import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.RerankCapable;
import com.smart.rag.infrastructure.llm.RerankRequest;
import com.smart.rag.infrastructure.llm.RerankResult;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.rag.config.RagRetrievalProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rerank 后处理器 — 适配 SPI 层 {@link RerankCapable} 为 Spring AI {@link DocumentPostProcessor}
 * <p>
 * 从 {@link LlmClientRegistry} 获取默认的 Rerank 客户端执行语义精排，
 * 代替原先的 {@link BailianRerankPostProcessor}（硬编码百炼 API）。
 */
public class RerankDocumentPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(RerankDocumentPostProcessor.class);

    private final RerankCapable reranker;
    private final int topN;

    public RerankDocumentPostProcessor(RerankCapable reranker, int topN) {
        this.reranker = reranker;
        this.topN = topN;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        // R1-M8 降级契约：空文档 → 空；空/blank 查询 → 原样透传（无语义无法精排）；
        // rerank 返回空结果 → 原样透传 + warn（见下）。任一降级均不抛异常，保证检索链路不中断。
        if (documents == null || documents.isEmpty()) return List.of();
        if (query == null || query.text() == null || query.text().isBlank()) return documents;

        List<String> docTexts = documents.stream()
            .map(Document::getText)
            .toList();

        RerankRequest request = new RerankRequest(query.text(), docTexts);
        List<RerankResult> results;
        try {
            results = reranker.rerank(request, topN);
        } catch (RuntimeException e) {
            // R1-M8 降级契约：rerank 失败（API 错误/超时/熔断）→ 原样透传，不中断检索与 chat 链路。
            // 不抛异常是硬契约——否则 rerank 任何失败会传播到 chat call，触发跨模型 fallback 误判。
            log.warn("Rerank failed, returning original order (query='{}'): {}", query.text(), e.getMessage());
            return documents;
        }

        if (results.isEmpty()) {
            log.warn("Rerank returned empty results for query: {}", query.text());
            return documents;
        }

        List<Document> reranked = new ArrayList<>(results.size());
        for (RerankResult rr : results) {
            if (rr.originalIndex() < 0 || rr.originalIndex() >= documents.size()) continue;
            Document original = documents.get(rr.originalIndex());
            Map<String, Object> metadata = original.getMetadata() != null
                ? new HashMap<>(original.getMetadata()) : new HashMap<>();
            metadata.put("rerankScore", rr.score());
            reranked.add(new Document(original.getId(), original.getText(), metadata));
        }

        log.debug("Rerank completed: {} docs -> {} docs", documents.size(), reranked.size());
        return reranked;
    }
}
