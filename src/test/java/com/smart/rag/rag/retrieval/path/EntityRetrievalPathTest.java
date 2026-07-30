package com.smart.rag.rag.retrieval.path;

import com.smart.rag.infrastructure.concurrent.DefaultScopedTasks;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.rag.retrieval.ScoredDocument;
import com.smart.rag.rag.retrieval.entity.EntityExpansionRetriever;
import com.smart.rag.rag.retrieval.entity.EntityFrontierRanker;
import com.smart.rag.rag.retrieval.entity.EntitySeedExtractor;
import com.smart.rag.rag.retrieval.entity.EntityVoteRetriever;
import com.smart.rag.rag.retrieval.entity.ExpandedChunk;
import com.smart.rag.rag.retrieval.entity.ScoredEntity;
import com.smart.rag.rag.retrieval.entity.VotedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link EntityRetrievalPath} 编排单元测试。
 * <p>
 * Mock 4 个组件，验证：PC1→PC2-3→PC4a∥PC4b→PC5 合并去重 + trace 结构 + RetrievalPath 契约。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntityRetrievalPath — 编排 + merge + trace")
class EntityRetrievalPathTest {

    @Mock
    private EntitySeedExtractor seedExtractor;
    @Mock
    private EntityFrontierRanker frontierRanker;
    @Mock
    private EntityVoteRetriever voteRetriever;
    @Mock
    private EntityExpansionRetriever expansionRetriever;

    private ScopedTasks scopedTasks;
    private EntityRetrievalPath path;

    @BeforeEach
    void setUp() {
        scopedTasks = new DefaultScopedTasks();
        path = new EntityRetrievalPath(seedExtractor, frontierRanker, voteRetriever, expansionRetriever, scopedTasks);
    }

    @Nested
    @DisplayName("RetrievalPath 契约（AC2）")
    class RetrievalPathContract {

        @Test
        @DisplayName("name() = 'entity-search'")
        void name_isEntitySearch() {
            assertThat(path.name()).isEqualTo("entity-search");
        }

        @Test
        @DisplayName("rrfWeighting() = SCORE_WEIGHTED")
        void rrfWeighting_isScoreWeighted() {
            assertThat(path.rrfWeighting()).isEqualTo(
                    com.smart.rag.rag.retrieval.RetrievalPath.RrfWeighting.SCORE_WEIGHTED);
        }
    }

    @Nested
    @DisplayName("编排流程")
    class Orchestration {

        @Test
        @DisplayName("空 seed → 空结果，不调 ranker/vote/expand")
        void search_emptySeeds_emptyResult() {
            when(seedExtractor.extract(anyString())).thenReturn(List.of());

            List<ScoredDocument> result = path.search("查询", 1L, null);

            assertThat(result).isEmpty();
            verifyNoInteractions(frontierRanker, voteRetriever, expansionRetriever);
        }

        @Test
        @DisplayName("空 frontier → 空结果，不调 vote/expand")
        void search_emptyFrontier_emptyResult() {
            when(seedExtractor.extract(anyString())).thenReturn(List.of("PostgreSQL"));
            when(frontierRanker.rank(anyList(), anyLong(), any())).thenReturn(List.of());

            List<ScoredDocument> result = path.search("查询", 1L, null);

            assertThat(result).isEmpty();
            verifyNoInteractions(voteRetriever, expansionRetriever);
        }

        @Test
        @DisplayName("vote + expand 均非空 → 合并去重（AC6：expand 发现不在 vote 集中的 chunk）")
        void search_voteAndExpand_mergedDedup() {
            when(seedExtractor.extract(anyString())).thenReturn(List.of("PostgreSQL"));
            when(frontierRanker.rank(anyList(), anyLong(), any())).thenReturn(frontier());

            UUID chunkA = UUID.randomUUID();
            UUID chunkB = UUID.randomUUID();  // 仅 vote
            UUID chunkC = UUID.randomUUID();  // 仅 expand

            when(voteRetriever.retrieve(anyList(), anyLong())).thenReturn(List.of(
                    new VotedChunk(chunkA, "content A", "{}", 0.9, "PostgreSQL"),
                    new VotedChunk(chunkB, "content B", "{}", 0.7, "pgvector")));
            when(expansionRetriever.retrieve(anyList(), anyLong(), any())).thenReturn(List.of(
                    new ExpandedChunk(chunkC, "content C", "{}", 0.63, "3,5"),
                    new ExpandedChunk(chunkA, "content A", "{}", 0.63, "3")));  // 与 vote 重叠 → 取 max(0.9, 0.63)=0.9

            List<ScoredDocument> result = path.search("查询", 1L, null);

            // 3 个唯一 chunk（A 合并了 vote + expand）
            assertThat(result).hasSize(3);
            // chunkA 排首位（score 0.9，max of vote+expand）
            assertThat(result.get(0).doc().getId()).isEqualTo(chunkA.toString());
            assertThat(result.get(0).score()).isEqualTo(0.9);
            // rank 从 1 开始连续
            assertThat(result.get(0).rank()).isEqualTo(1);
            assertThat(result.get(1).rank()).isEqualTo(2);
            assertThat(result.get(2).rank()).isEqualTo(3);
            // metadata 含 path=C
            assertThat(result.get(0).doc().getMetadata()).containsEntry("path", "C");
        }

        @Test
        @DisplayName("vote 子任务异常 → 优雅降级为空（不影响 expand 结果）")
        void search_voteThrows_degradesToExpandOnly() {
            when(seedExtractor.extract(anyString())).thenReturn(List.of("PostgreSQL"));
            when(frontierRanker.rank(anyList(), anyLong(), any())).thenReturn(frontier());
            when(voteRetriever.retrieve(anyList(), anyLong()))
                    .thenThrow(new RuntimeException("DB error"));
            UUID chunkC = UUID.randomUUID();
            when(expansionRetriever.retrieve(anyList(), anyLong(), any())).thenReturn(List.of(
                    new ExpandedChunk(chunkC, "content C", "{}", 0.63, "3")));

            List<ScoredDocument> result = path.search("查询", 1L, null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).doc().getId()).isEqualTo(chunkC.toString());
        }
    }

    private List<ScoredEntity> frontier() {
        return List.of(new ScoredEntity(1L, "PostgreSQL", 0.9, 3, 0.5, 2, 1.0, 0.5, 0.625, 0.625));
    }
}
