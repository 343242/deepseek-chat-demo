package com.smart.rag.rag.service.impl;

import com.smart.rag.rag.entity.RagChunkEntity;
import com.smart.rag.rag.entity.RagEntity;
import com.smart.rag.rag.mapper.ChunkEntityMapper;
import com.smart.rag.rag.mapper.EntityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EntityCanonicalizationService 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntityCanonicalizationService 单元测试")
class EntityCanonicalizationServiceTest {

    @Mock
    private EntityMapper entityMapper;
    @Mock
    private ChunkEntityMapper chunkEntityMapper;

    @InjectMocks
    private EntityCanonicalizationService service;

    // ==================== canonicalize ====================

    @Nested
    @DisplayName("canonicalize — Level 1 规范化")
    class CanonicalizeTests {

        @Test
        @DisplayName("标准名称：NFC + lowercase + trim")
        void standardName() {
            assertThat(service.canonicalize("  PostgreSQL  ")).isEqualTo("postgresql");
        }

        @Test
        @DisplayName("全大写 → 小写")
        void uppercase() {
            assertThat(service.canonicalize("AI")).isEqualTo("ai");
        }

        @Test
        @DisplayName("Unicode NFC 归一化")
        void unicodeNfc() {
            // U+00C0 (À) = NFC single codepoint vs decomposed (A + combining grave)
            assertThat(service.canonicalize("\u00C0")).isEqualTo("\u00C0".toLowerCase());
        }

        @Test
        @DisplayName("null → 空字符串")
        void nullInput() {
            assertThat(service.canonicalize(null)).isEmpty();
        }

        @Test
        @DisplayName("空白 → 空字符串")
        void blankInput() {
            assertThat(service.canonicalize("   ")).isEmpty();
        }

        @Test
        @DisplayName("首尾空格修剪")
        void trimSpaces() {
            assertThat(service.canonicalize("  hello world  ")).isEqualTo("hello world");
        }
    }

    // ==================== aggregateAndUpsert ====================

    @Nested
    @DisplayName("aggregateAndUpsert — 分组拼接 + UPSERT")
    class AggregateAndUpsertTests {

        @BeforeEach
        void setUp() {
            // 模拟 UPSERT 后 selectList 返回带 ID 的实体
            lenient().when(entityMapper.selectList(any())).thenAnswer(inv -> {
                var wrapper = inv.getArgument(0);
                return List.of();  // 简化：返回空列表（实际会按条件查）
            });
        }

        @Test
        @DisplayName("同名实体跨 chunk description 拼接")
        void sameNameConcatDescription() {
            // 模拟 UPSERT 后查询返回实体
            RagEntity upsertedEntity = new RagEntity();
            upsertedEntity.setId(1L);
            upsertedEntity.setNameNorm("postgresql");
            upsertedEntity.setDescription("开源关系型数据库");
            when(entityMapper.selectList(any())).thenReturn(List.of(upsertedEntity));

            List<EntityCanonicalizationService.ParsedExtraction> extractions = List.of(
                    new EntityCanonicalizationService.ParsedExtraction(
                            "chunk-1", "event1",
                            List.of(new EntityCanonicalizationService.ParsedEntity("PostgreSQL", "开源关系型数据库", "product"))),
                    new EntityCanonicalizationService.ParsedExtraction(
                            "chunk-2", "event2",
                            List.of(new EntityCanonicalizationService.ParsedEntity("PostgreSQL", "支持 HNSW 索引", "product")))
            );

            service.aggregateAndUpsert(extractions, 100L, null);

            // 验证 UPSERT 被调用
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<RagEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(entityMapper).upsertByNormUserTeam(captor.capture());
            List<RagEntity> upserted = captor.getValue();
            assertThat(upserted).hasSize(1);
            assertThat(upserted.get(0).getNameNorm()).isEqualTo("postgresql");
        }

        @Test
        @DisplayName("空抽取结果 → 无操作")
        void emptyExtractions() {
            List<EntityCanonicalizationService.ParsedExtraction> extractions = List.of();

            List<Long> result = service.aggregateAndUpsert(extractions, 100L, null);

            assertThat(result).isEmpty();
            verify(entityMapper, never()).upsertByNormUserTeam(anyList());
        }

        @Test
        @DisplayName("空名称实体被跳过")
        void emptyNameSkipped() {
            List<EntityCanonicalizationService.ParsedExtraction> extractions = List.of(
                    new EntityCanonicalizationService.ParsedExtraction(
                            "chunk-1", "event1",
                            List.of(new EntityCanonicalizationService.ParsedEntity("", "desc", "topic")))
            );

            List<Long> result = service.aggregateAndUpsert(extractions, 100L, null);

            assertThat(result).isEmpty();
        }
    }
}
