package com.smart.rag.rag.retrieval.entity;

import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.mapper.EntityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link EntityExpansionRetriever} 单元测试。
 * <p>
 * 核心验收 AC7：expansionHops <= 0 时返回空列表，不执行 SQL（干净禁用路径）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntityExpansionRetriever — SAG 扩展 + H=0 禁用")
class EntityExpansionRetrieverTest {

    @Mock
    private EntityMapper entityMapper;

    private RagEntityProperties properties;
    private EntityExpansionRetriever retriever;

    @BeforeEach
    void setUp() {
        properties = new RagEntityProperties(10, 500, 0.85, 50, 20, 10, 1, 0.7, 0.5, 0.3, 0.2, true, null, true);
        retriever = new EntityExpansionRetriever(entityMapper, properties);
    }

    @Nested
    @DisplayName("H=0 禁用路径（AC7）")
    class ExpansionDisabled {

        @Test
        @DisplayName("expansionHops=0 → 返回空列表，不调用 mapper")
        void retrieve_hopsZero_emptyNoSql() {
            properties = new RagEntityProperties(10, 500, 0.85, 50, 20, 10, 0, 0.7, 0.5, 0.3, 0.2, true, null, true);
            retriever = new EntityExpansionRetriever(entityMapper, properties);

            List<ExpandedChunk> result = retriever.retrieve(frontier(), 1L, null);

            assertThat(result).isEmpty();
            verifyNoInteractions(entityMapper);
        }

        @Test
        @DisplayName("expansionHops=1（默认）→ 调用 mapper，返回结果")
        void retrieve_hopsOne_callsMapper() {
            when(entityMapper.expandChunks(anyList(), anyDouble(), anyInt(), anyLong(), any(), anyString()))
                    .thenReturn(List.of());

            List<ExpandedChunk> result = retriever.retrieve(frontier(), 1L, null);

            assertThat(result).isEmpty();
            verify(entityMapper).expandChunks(anyList(), eq(0.7), eq(10), eq(1L), eq(null), eq("1"));
        }
    }

    @Nested
    @DisplayName("边界")
    class EdgeCases {

        @Test
        @DisplayName("空 frontier → 空列表，不调 mapper（即使 H>0）")
        void retrieve_emptyFrontier_empty() {
            List<ExpandedChunk> result = retriever.retrieve(List.of(), 1L, null);
            assertThat(result).isEmpty();
            verifyNoInteractions(entityMapper);
        }

        @Test
        @DisplayName("null frontier → 空列表")
        void retrieve_nullFrontier_empty() {
            List<ExpandedChunk> result = retriever.retrieve(null, 1L, null);
            assertThat(result).isEmpty();
        }
    }

    private List<ScoredEntity> frontier() {
        return List.of(new ScoredEntity(1L, "PostgreSQL", 0.9, 3, 0.5, 2, 1.0, 0.5, 0.625, 0.625));
    }
}
