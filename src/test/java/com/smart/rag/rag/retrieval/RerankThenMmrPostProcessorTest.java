package com.smart.rag.rag.retrieval;

import com.smart.rag.infrastructure.llm.RerankCapable;
import com.smart.rag.infrastructure.llm.RerankResult;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * RerankThenMmrPostProcessor 单测 —— 覆盖 CompletableFuture 并行编排 + 4 条降级路径（B1/B2）。
 * 用真实 RerankDocumentPostProcessor + MmrDocumentPostProcessor（mock 底层 reranker/mapper），
 * 端到端验证复合处理器的降级契约。
 */
@DisplayName("RerankThenMmrPostProcessor")
@ExtendWith(MockitoExtension.class)
class RerankThenMmrPostProcessorTest {

    @Mock
    private RerankCapable reranker;
    @Mock
    private VectorStoreMapper vectorStoreMapper;

    private Query query;

    @BeforeEach
    void setUp() {
        query = new Query("test query");
    }

    private RerankThenMmrPostProcessor newProcessor(int rerankTopN, int mmrTopK) {
        RerankDocumentPostProcessor rerankPP = new RerankDocumentPostProcessor(reranker, rerankTopN);
        MmrDocumentPostProcessor mmrPP = new MmrDocumentPostProcessor(0.7, mmrTopK, 60, vectorStoreMapper);
        return new RerankThenMmrPostProcessor(rerankPP, mmrPP, Executors.newVirtualThreadPerTaskExecutor());
    }

    private Document doc(String id, Map<String, Object> metadata) {
        return new Document(id, "content of " + id, metadata);
    }

    @Test
    @DisplayName("正常路径：rerank 精排 + distance 预取 → MMR 贪心返回 topK")
    void normalPath_rerankThenMmr() {
        var processor = newProcessor(3, 2);
        var docs = List.of(doc("d1", Map.of()), doc("d2", Map.of()), doc("d3", Map.of()), doc("d4", Map.of()));
        when(reranker.rerank(any(), anyInt())).thenReturn(List.of(
                new RerankResult(0, 0.9, "c1"),
                new RerankResult(1, 0.8, "c2"),
                new RerankResult(2, 0.7, "c3")
        ));
        when(vectorStoreMapper.pairwiseCosineDistance(any(), anyInt())).thenReturn(Map.of());

        List<Document> result = processor.process(query, docs);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("rerank 异常 → .exceptionally 透传原文档（B2：无静默吞，降级不中断）")
    void rerankException_passthrough() {
        var processor = newProcessor(3, 2);
        var docs = List.of(
                doc("d1", Map.of("rrfScore", 0.5)),
                doc("d2", Map.of("rrfScore", 0.4)),
                doc("d3", Map.of("rrfScore", 0.3))
        );
        when(reranker.rerank(any(), anyInt())).thenThrow(new RuntimeException("API down"));
        when(vectorStoreMapper.pairwiseCosineDistance(any(), anyInt())).thenReturn(Map.of());

        List<Document> result = processor.process(query, docs);

        // rerank 透传原文档(3)，MMR 从 3 选 2（空距离矩阵→sim=0→纯 relevance by rrfScore）
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("d1");
    }

    @Test
    @DisplayName("distance 异常 → null → relevance-only 降级（按 rerankScore 取 topK）")
    void distanceException_relevanceOnly() {
        var processor = newProcessor(3, 2);
        var docs = List.of(doc("d1", Map.of()), doc("d2", Map.of()), doc("d3", Map.of()), doc("d4", Map.of()));
        when(reranker.rerank(any(), anyInt())).thenReturn(List.of(
                new RerankResult(0, 0.9, "c1"),
                new RerankResult(1, 0.3, "c2"),
                new RerankResult(2, 0.6, "c3")
        ));
        when(vectorStoreMapper.pairwiseCosineDistance(any(), anyInt())).thenThrow(new RuntimeException("DB down"));

        List<Document> result = processor.process(query, docs);

        // distance 失败→null→relevance-only，reranked(3) 选 top2: d1(0.9), d3(0.6)
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("d1");
        assertThat(result.get(1).getId()).isEqualTo("d3");
    }

    @Test
    @DisplayName("rerank + distance 均失败 → 透传 + relevance-only，不抛异常")
    void bothFail_gracefulDegrade() {
        var processor = newProcessor(2, 2);
        var docs = List.of(
                doc("d1", Map.of("rrfScore", 0.9)),
                doc("d2", Map.of("rrfScore", 0.3)),
                doc("d3", Map.of("rrfScore", 0.5))
        );
        when(reranker.rerank(any(), anyInt())).thenThrow(new RuntimeException("API down"));
        when(vectorStoreMapper.pairwiseCosineDistance(any(), anyInt())).thenThrow(new RuntimeException("DB down"));

        List<Document> result = processor.process(query, docs);

        // rerank 透传原文档(3) + distance null → relevance-only 取 top2: d1(0.9), d3(0.5)
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("d1");
        assertThat(result.get(1).getId()).isEqualTo("d3");
    }

    @Test
    @DisplayName("null query → rerankOnly 透传原文档，MMR 用 rrfScore（Query 构造器已拦 blank text，此处覆盖 null 守卫）")
    void nullQuery_passthrough() {
        var processor = newProcessor(2, 2);
        var docs = List.of(
                doc("d1", Map.of("rrfScore", 0.8)),
                doc("d2", Map.of("rrfScore", 0.2)),
                doc("d3", Map.of("rrfScore", 0.5))
        );
        when(vectorStoreMapper.pairwiseCosineDistance(any(), anyInt())).thenReturn(Map.of());

        List<Document> result = processor.process(null, docs);

        // null query → rerankOnly 透传(3)，MMR 从 3 选 2（relevance by rrfScore）
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("d1");
    }

    @Test
    @DisplayName("空文档 → 返回空")
    void emptyDocs_returnsEmpty() {
        var processor = newProcessor(2, 2);
        assertThat(processor.process(query, List.of())).isEmpty();
        assertThat(processor.process(query, null)).isEmpty();
    }
}
