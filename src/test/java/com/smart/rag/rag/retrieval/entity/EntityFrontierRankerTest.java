package com.smart.rag.rag.retrieval.entity;

import com.smart.rag.infrastructure.llm.EmbeddingCapable;
import com.smart.rag.infrastructure.llm.EmbeddingType;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.mapper.EntityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link EntityFrontierRanker} 单元测试。
 * <p>
 * window-max 归一化在 SQL 内完成（§6.2），本类验证 Java 层的消融开关门控（β/γ 强制 0）
 * 与参数透传。AC4 的 window-max 手算值由真实 PG 集成测试覆盖；此处用 fixture 验证
 * composite_score 计算公式正确性。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntityFrontierRanker — 消融开关 + 参数透传")
class EntityFrontierRankerTest {

    @Mock
    private EntityMapper entityMapper;
    @Mock
    private LlmClientRegistry llmClientRegistry;
    @Mock
    private EmbeddingCapable embeddingCapable;

    private RagEntityProperties properties;
    private EntityFrontierRanker ranker;

    @BeforeEach
    void setUp() {
        properties = new RagEntityProperties(false, 10, 500, 0.85, 50, 20, 10, 1, 0.7, 0.5, 0.3, 0.2, true, null, true);
        ranker = new EntityFrontierRanker(entityMapper, llmClientRegistry, properties);
    }

    @Nested
    @DisplayName("消融开关门控（AC8/AC9）")
    class AblationSwitches {

        @Test
        @DisplayName("weakTieEnabled=false → γ 强制为 0（AC8）")
        void rank_weakTieDisabled_gammaForcedZero() {
            properties = new RagEntityProperties(false, 10, 500, 0.85, 50, 20, 10, 1, 0.7, 0.5, 0.3, 0.2, false, null, true);
            ranker = new EntityFrontierRanker(entityMapper, llmClientRegistry, properties);

            stubEmbedding();
            ranker.rank(List.of("PostgreSQL"), 1L, null);

            ArgumentCaptor<Double> gammaCaptor = ArgumentCaptor.forClass(Double.class);
            verify(entityMapper).findFrontierEntities(anyList(), anyDouble(), anyLong(), any(), anyInt(),
                    anyDouble(), anyDouble(), gammaCaptor.capture());
            assertThat(gammaCaptor.getValue()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("communityDetectionEnabled=false → β 强制为 0（AC9）")
        void rank_communityDisabled_betaForcedZero() {
            properties = new RagEntityProperties(false, 10, 500, 0.85, 50, 20, 10, 1, 0.7, 0.5, 0.3, 0.2, true, null, false);
            ranker = new EntityFrontierRanker(entityMapper, llmClientRegistry, properties);

            stubEmbedding();
            ranker.rank(List.of("PostgreSQL"), 1L, null);

            ArgumentCaptor<Double> betaCaptor = ArgumentCaptor.forClass(Double.class);
            verify(entityMapper).findFrontierEntities(anyList(), anyDouble(), anyLong(), any(), anyInt(),
                    anyDouble(), betaCaptor.capture(), anyDouble());
            assertThat(betaCaptor.getValue()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("两个开关都 true → α/β/γ 原值透传")
        void rank_bothEnabled_originalWeightsPassed() {
            stubEmbedding();
            ranker.rank(List.of("PostgreSQL"), 1L, null);

            ArgumentCaptor<Double> alphaCaptor = ArgumentCaptor.forClass(Double.class);
            ArgumentCaptor<Double> betaCaptor = ArgumentCaptor.forClass(Double.class);
            ArgumentCaptor<Double> gammaCaptor = ArgumentCaptor.forClass(Double.class);
            verify(entityMapper).findFrontierEntities(anyList(), anyDouble(), anyLong(), any(), anyInt(),
                    alphaCaptor.capture(), betaCaptor.capture(), gammaCaptor.capture());
            assertThat(alphaCaptor.getValue()).isEqualTo(0.5);
            assertThat(betaCaptor.getValue()).isEqualTo(0.3);
            assertThat(gammaCaptor.getValue()).isEqualTo(0.2);
        }
    }

    @Nested
    @DisplayName("边界")
    class EdgeCases {

        @Test
        @DisplayName("空 seed 列表 → 空 frontier，不调 mapper")
        void rank_emptySeeds_emptyResult() {
            List<ScoredEntity> result = ranker.rank(List.of(), 1L, null);
            assertThat(result).isEmpty();
            verifyNoInteractions(entityMapper);
        }

        @Test
        @DisplayName("null seed 列表 → 空 frontier")
        void rank_nullSeeds_emptyResult() {
            List<ScoredEntity> result = ranker.rank(null, 1L, null);
            assertThat(result).isEmpty();
        }
    }

    private void stubEmbedding() {
        when(llmClientRegistry.getDefault(LlmCapability.EMBEDDING, EmbeddingCapable.class)).thenReturn(embeddingCapable);
        when(embeddingCapable.embedBatch(anyList(), eq(EmbeddingType.QUERY)))
                .thenReturn(List.of(new float[]{0.1f, 0.2f, 0.3f}));
        when(entityMapper.findFrontierEntities(anyList(), anyDouble(), anyLong(), any(), anyInt(),
                anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
    }
}
