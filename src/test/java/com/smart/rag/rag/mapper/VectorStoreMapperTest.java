package com.smart.rag.rag.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * VectorStoreMapper default 方法单元测试。
 * <p>
 * SQL 已迁移到 MyBatis XML（{@code mapper/VectorStoreMapper.xml}），故这里只验证 default
 * 方法里的 Java 业务逻辑：对称距离矩阵、metadata 组装、空输入守卫、O(n²) 截断、parentId 归并等。
 * 抽象查询方法用 mock 桩；SQL 正确性由真实 PG 冒烟测试覆盖。
 * <p>
 * 技巧：{@link Answers#CALLS_REAL_METHODS} 让 mock 跑真实 default 方法，抽象方法返回默认值或被桩覆盖。
 */
class VectorStoreMapperTest {

    private VectorStoreMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = mock(VectorStoreMapper.class, Answers.CALLS_REAL_METHODS);
    }

    // ========================================================================
    // pairwiseCosineDistance
    // ========================================================================

    @Nested
    @DisplayName("pairwiseCosineDistance")
    class PairwiseCosineDistance {

        @Test
        @DisplayName("少于 2 个 ID / null 时返回空 Map 且不查库")
        void pairwise_lessThanTwo() {
            assertThat(mapper.pairwiseCosineDistance(null)).isEmpty();
            assertThat(mapper.pairwiseCosineDistance(List.of())).isEmpty();
            assertThat(mapper.pairwiseCosineDistance(List.of("id1"))).isEmpty();
            verifyNoInteractionsOnSelects();
        }

        @Test
        @DisplayName("构建对称距离矩阵：a|b 与 b|a 都存在")
        void pairwise_symmetricMatrix() {
            when(mapper.selectPairwiseDistance(anyList())).thenReturn(List.of(
                    new VectorStoreMapper.PairwiseDistanceRow("a", "b", 0.25),
                    new VectorStoreMapper.PairwiseDistanceRow("a", "c", 0.8)
            ));

            Map<String, Double> result = mapper.pairwiseCosineDistance(List.of("a", "b", "c"));

            assertThat(result).hasSize(4);
            assertThat(result.get("a|b")).isEqualTo(0.25);
            assertThat(result.get("b|a")).isEqualTo(0.25);
            assertThat(result.get("a|c")).isEqualTo(0.8);
            assertThat(result.get("c|a")).isEqualTo(0.8);
        }

        @Test
        @DisplayName("超过 MAX_PAIRWISE_DOCS=50 时截断后再查库")
        void pairwise_truncation() {
            when(mapper.selectPairwiseDistance(anyList())).thenReturn(List.of());

            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 51; i++) {
                ids.add("id" + i);
            }

            mapper.pairwiseCosineDistance(ids);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
            verify(mapper).selectPairwiseDistance(captor.capture());
            assertThat(captor.getValue()).hasSize(VectorStoreMapper.MAX_PAIRWISE_DOCS);
        }
    }

    // ========================================================================
    // bm25Search
    // ========================================================================

    @Nested
    @DisplayName("bm25Search")
    class Bm25Search {

        @Test
        @DisplayName("行 → Document，metadata 注入 retrievalSource=bm25")
        void bm25Search_buildsDocWithSource() {
            when(mapper.selectBm25Rows(any(), any(), any(), any(), anyInt())).thenReturn(List.of(
                    new VectorStoreMapper.VectorStoreRow("d1", "content-1", new LinkedHashMap<>(Map.of("doc", 1)))
            ));

            List<Document> result = mapper.bm25Search("jiebacfg", "查询", "userId", "123", 10);

            assertThat(result).hasSize(1);
            Document doc = result.get(0);
            assertThat(doc.getId()).isEqualTo("d1");
            assertThat(doc.getText()).isEqualTo("content-1");
            assertThat(doc.getMetadata()).containsEntry("retrievalSource", "bm25");
            assertThat(doc.getMetadata()).containsEntry("doc", 1);
        }

        @Test
        @DisplayName("metadata 为 null 时降级为空 map 再注入 retrievalSource")
        void bm25Search_nullMetadataSafe() {
            when(mapper.selectBm25Rows(any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of(new VectorStoreMapper.VectorStoreRow("d2", "c", null)));

            List<Document> result = mapper.bm25Search("jiebacfg", "q", "userId", "1", 5);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getMetadata()).containsEntry("retrievalSource", "bm25");
        }
    }

    // ========================================================================
    // batchFetchParents
    // ========================================================================

    @Nested
    @DisplayName("batchFetchParents")
    class BatchFetchParents {

        @Test
        @DisplayName("空 parentIds 直接返回空 Map 且不查库")
        void batchFetchParents_emptyInput() {
            assertThat(mapper.batchFetchParents(null)).isEmpty();
            assertThat(mapper.batchFetchParents(Set.of())).isEmpty();
            verify(mapper, never()).selectParentRows(anySet());
        }

        @Test
        @DisplayName("按 metadata.parentId 归并为 parentId → Document")
        void batchFetchParents_indexByParentId() {
            when(mapper.selectParentRows(anySet())).thenReturn(List.of(
                    new VectorStoreMapper.VectorStoreRow("child1", "p-content", Map.of("parentId", "P1")),
                    new VectorStoreMapper.VectorStoreRow("child2", "p-content", Map.of("parentId", "P2"))
            ));

            Map<String, Document> result = mapper.batchFetchParents(Set.of("P1", "P2"));

            assertThat(result).containsOnlyKeys("P1", "P2");
            assertThat(result.get("P1").getId()).isEqualTo("child1");
        }
    }

    // ========================================================================
    // insertFastTrackRow
    // ========================================================================

    @Nested
    @DisplayName("insertFastTrackRow")
    class InsertFastTrackRow {

        @Test
        @DisplayName("metadata 含 documentId/userId/fastTrack=true，teamId 缺省")
        void insertFastTrackRow_noTeamId() {
            mapper.insertFastTrackRow(100L, "内容", 1L, null, "report.pdf");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(mapper).insertFastTrackRowInternal(eq("内容"), captor.capture());

            Map<String, Object> metadata = captor.getValue();
            assertThat(metadata).containsEntry("documentId", "100");
            assertThat(metadata).containsEntry("userId", "1");
            assertThat(metadata).containsEntry("fileName", "report.pdf");
            assertThat(metadata).containsEntry("fastTrack", true);
            assertThat(metadata).doesNotContainKey("teamId");
        }

        @Test
        @DisplayName("带 teamId 时 metadata 含 teamId")
        void insertFastTrackRow_withTeamId() {
            mapper.insertFastTrackRow(200L, "内容", 1L, 99L, "guide.pdf");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(mapper).insertFastTrackRowInternal(eq("内容"), captor.capture());

            assertThat(captor.getValue()).containsEntry("teamId", "99");
        }
    }

    // ========================================================================
    // deleteFastTrackRows
    // ========================================================================

    @Nested
    @DisplayName("deleteFastTrackRows")
    class DeleteFastTrackRows {

        @Test
        @DisplayName("把 Long documentId 转成 String 再传给底层删除")
        void deleteFastTrackRows_passesStringId() {
            mapper.deleteFastTrackRows(100L);
            verify(mapper).deleteFastTrackRowsInternal("100");
        }
    }

    // ========================================================================
    // fetchDocHighlights
    // ========================================================================

    @Nested
    @DisplayName("fetchDocHighlights")
    class FetchDocHighlights {

        @Test
        @DisplayName("行 → 有序 LinkedHashMap（保持 DB 返回顺序）")
        void fetchDocHighlights_preservesOrder() {
            when(mapper.selectHighlightRows(anyList(), eq("查询"), eq("jiebacfg"))).thenReturn(List.of(
                    new VectorStoreMapper.HighlightRow("d3", "<mark>x</mark>"),
                    new VectorStoreMapper.HighlightRow("d1", "<mark>y</mark>")
            ));

            Map<String, String> result = mapper.fetchDocHighlights(List.of("d3", "d1"), "查询", "jiebacfg");

            assertThat(result).containsKeys("d3", "d1");
            assertThat(new ArrayList<>(result.keySet())).containsExactly("d3", "d1");
        }

        @Test
        @DisplayName("空 docIds 直接返回空 Map 且不查库")
        void fetchDocHighlights_emptyInput() {
            assertThat(mapper.fetchDocHighlights(null, "q", "jiebacfg")).isEmpty();
            assertThat(mapper.fetchDocHighlights(List.of(), "q", "jiebacfg")).isEmpty();
            verify(mapper, never()).selectHighlightRows(anyList(), anyString(), anyString());
        }
    }

    // ========================================================================
    // countDocs
    // ========================================================================

    @Nested
    @DisplayName("countDocs")
    class CountDocs {

        @Test
        @DisplayName("透传到底层查询")
        void countDocs_delegates() {
            when(mapper.countDocsInternal("userId", "123")).thenReturn(42);
            assertThat(mapper.countDocs("userId", "123")).isEqualTo(42);
        }
    }

    private void verifyNoInteractionsOnSelects() {
        verify(mapper, never()).selectPairwiseDistance(anyList());
        verify(mapper, never()).selectParentRows(anySet());
        verify(mapper, never()).selectHighlightRows(anyList(), anyString(), anyString());
    }
}
