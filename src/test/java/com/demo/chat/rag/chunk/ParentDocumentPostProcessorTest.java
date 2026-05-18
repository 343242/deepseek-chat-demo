package com.demo.chat.rag.chunk;

import com.demo.chat.rag.mapper.VectorStoreMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ParentDocumentPostProcessor")
class ParentDocumentPostProcessorTest {

    @Mock
    private VectorStoreMapper vectorStoreMapper;

    private final Query testQuery = new Query("test query");

    private Document childDoc(String id, String parentId, Map<String, Object> extraMeta) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(ParentChildChunkStrategy.META_PARENT_ID, parentId);
        meta.put(ParentChildChunkStrategy.META_IS_PARENT, false);
        if (extraMeta != null) meta.putAll(extraMeta);
        return new Document(id, "child content of " + parentId, meta);
    }

    private Document parentDoc(String id, String content) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(ParentChildChunkStrategy.META_IS_PARENT, true);
        meta.put(ParentChildChunkStrategy.META_PARENT_ID, id);  // 父文档 metadata 中也有 parentId
        return new Document(id, content, meta);
    }

    private Document nonChildDoc(String id) {
        return new Document(id, "standalone content", Map.of());
    }

    @Nested
    @DisplayName("resolveScore")
    class ResolveScoreTest {

        @Test
        @DisplayName("rerankScore 优先于 rrfScore")
        void rerankScoreTakesPriority() {
            Document doc = new Document("1", "text",
                    Map.of("rerankScore", 0.95, "rrfScore", 0.5));
            assertThat(ParentDocumentPostProcessor.resolveScore(doc)).isEqualTo(0.95);
        }

        @Test
        @DisplayName("仅有 rrfScore 时使用 rrfScore")
        void rrfScoreFallback() {
            Document doc = new Document("1", "text", Map.of("rrfScore", 0.7));
            assertThat(ParentDocumentPostProcessor.resolveScore(doc)).isEqualTo(0.7);
        }

        @Test
        @DisplayName("无分数 metadata 时使用默认值 0.5")
        void defaultScore() {
            Document doc = new Document("1", "text", Map.of());
            assertThat(ParentDocumentPostProcessor.resolveScore(doc)).isEqualTo(0.5);
        }
    }

    @Nested
    @DisplayName("Parent-level Rescoring")
    class RescoringTest {

        @Test
        @DisplayName("父文档按子块最高分数降序排列")
        void parentsSortedByMaxChildScore() {
            // 三个子块，属于两个父文档
            Document child1 = childDoc("c1", "p1", Map.of("rerankScore", 0.9));  // parent p1 score=0.9
            Document child2 = childDoc("c2", "p2", Map.of("rerankScore", 0.5));  // parent p2 score=0.5
            Document child3 = childDoc("c3", "p1", Map.of("rerankScore", 0.7));  // parent p1 max=0.9

            // 父文档回查
            Document parent1 = parentDoc("p1", "parent 1 content");
            Document parent2 = parentDoc("p2", "parent 2 content");
            when(vectorStoreMapper.batchFetchParents(any()))
                    .thenReturn(Map.of("p1", parent1, "p2", parent2));

            ParentDocumentPostProcessor processor = new ParentDocumentPostProcessor(vectorStoreMapper);
            List<Document> result = processor.process(testQuery, List.of(child1, child2, child3));

            // p1 (score 0.9) 应排在 p2 (score 0.5) 前面
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getId()).isEqualTo("p1");
            assertThat(result.get(1).getId()).isEqualTo("p2");
        }

        @Test
        @DisplayName("混合场景：父文档按分数排序，non-child 保持原序")
        void mixedParentAndNonChild() {
            Document child1 = childDoc("c1", "p1", Map.of("rrfScore", 0.3));
            Document nonChild = nonChildDoc("standalone");
            Document child2 = childDoc("c2", "p2", Map.of("rrfScore", 0.8));

            Document parent1 = parentDoc("p1", "parent 1 content");
            Document parent2 = parentDoc("p2", "parent 2 content");
            when(vectorStoreMapper.batchFetchParents(any()))
                    .thenReturn(Map.of("p1", parent1, "p2", parent2));

            ParentDocumentPostProcessor processor = new ParentDocumentPostProcessor(vectorStoreMapper);
            List<Document> result = processor.process(testQuery, List.of(child1, nonChild, child2));

            // 父文档部分：p2 (0.8) > p1 (0.3)
            // non-child 在后面
            assertThat(result).hasSize(3);
            assertThat(result.get(0).getId()).isEqualTo("p2");
            assertThat(result.get(1).getId()).isEqualTo("p1");
            assertThat(result.get(2).getId()).isEqualTo("standalone");
        }

        @Test
        @DisplayName("同一父文档多个子块取 max 分数")
        void maxScoreAggregation() {
            Document child1 = childDoc("c1", "p1", Map.of("rerankScore", 0.3));
            Document child2 = childDoc("c2", "p1", Map.of("rerankScore", 0.9));
            Document child3 = childDoc("c3", "p2", Map.of("rerankScore", 0.6));

            Document parent1 = parentDoc("p1", "parent 1 content");
            Document parent2 = parentDoc("p2", "parent 2 content");
            when(vectorStoreMapper.batchFetchParents(any()))
                    .thenReturn(Map.of("p1", parent1, "p2", parent2));

            ParentDocumentPostProcessor processor = new ParentDocumentPostProcessor(vectorStoreMapper);
            List<Document> result = processor.process(testQuery, List.of(child1, child2, child3));

            // p1 max=0.9 > p2 0.6
            assertThat(result.get(0).getId()).isEqualTo("p1");
            assertThat(result.get(1).getId()).isEqualTo("p2");
        }

        @Test
        @DisplayName("父文档回查失败时 fallback 到子块，仍参与排序")
        void fallbackToChildOnMissingParent() {
            Document child1 = childDoc("c1", "p1", Map.of("rerankScore", 0.9));
            Document child2 = childDoc("c2", "p2", Map.of("rerankScore", 0.5));

            // 只返回 p1，p2 缺失
            Document parent1 = parentDoc("p1", "parent 1 content");
            when(vectorStoreMapper.batchFetchParents(any()))
                    .thenReturn(Map.of("p1", parent1));

            ParentDocumentPostProcessor processor = new ParentDocumentPostProcessor(vectorStoreMapper);
            List<Document> result = processor.process(testQuery, List.of(child1, child2));

            assertThat(result).hasSize(2);
            // p1 (0.9) 排前面，p2 fallback 到子块 (0.5)
            assertThat(result.get(0).getId()).isEqualTo("p1");
            assertThat(result.get(1).getId()).isEqualTo("c2");
        }
    }

    @Nested
    @DisplayName("边界条件")
    class EdgeCaseTest {

        @Test
        @DisplayName("空列表直接返回")
        void emptyInput() {
            ParentDocumentPostProcessor processor = new ParentDocumentPostProcessor(vectorStoreMapper);
            List<Document> result = processor.process(testQuery, Collections.emptyList());
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("全是 non-child 文档时无排序")
        void allNonChild() {
            Document doc1 = nonChildDoc("a");
            Document doc2 = nonChildDoc("b");

            ParentDocumentPostProcessor processor = new ParentDocumentPostProcessor(vectorStoreMapper);
            List<Document> result = processor.process(testQuery, List.of(doc1, doc2));

            assertThat(result).containsExactly(doc1, doc2);
        }

        @Test
        @DisplayName("null 输入返回 null")
        void nullInput() {
            ParentDocumentPostProcessor processor = new ParentDocumentPostProcessor(vectorStoreMapper);
            List<Document> result = processor.process(testQuery, null);
            assertThat(result).isNull();
        }
    }
}
