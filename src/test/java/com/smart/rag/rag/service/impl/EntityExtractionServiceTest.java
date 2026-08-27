package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.concurrent.DefaultScopedTasks;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.entity.RagEvent;
import com.smart.rag.rag.event.EtlVectorizedEvent;
import com.smart.rag.rag.mapper.ChunkEntityMapper;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EventMapper;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.service.impl.EntityCanonicalizationService.AggregateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EntityExtractionService 单元测试（V30：graphChanged 门控 + 完成标记 + 增量过滤）。
 * <p>
 * ScopedTasks 用真实 {@link DefaultScopedTasks}（fork 实际在虚拟线程上执行），
 * 结构化并发行为一并覆盖；LLM/Mapper 均为替身。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntityExtractionService 单元测试（V30）")
class EntityExtractionServiceTest {

    @Mock
    private EntityCanonicalizationService canonicalizationService;
    @Mock
    private EntityEmbeddingService embeddingService;
    @Mock
    private EntityChatClientResolver chatClientResolver;
    @Mock
    private EntityMapper entityMapper;
    @Mock
    private EventMapper eventMapper;
    @Mock
    private ChunkEntityMapper chunkEntityMapper;
    @Mock
    private RagDocumentMapper documentMapper;
    @Mock
    private VectorStoreMapper vectorStoreMapper;
    @Mock
    private ChatCapable chatCapable;
    @Mock
    private DeriveDebouncer deriveDebouncer;

    private EntityExtractionService service;

    @BeforeEach
    void setUp() {
        RagEntityProperties properties = new RagEntityProperties(
                20, 500, 32, 0.85, 50, 20, 10, 1, 0.7, 0.5, 0.3, 0.2,
                true, null, true, 10_000L, 3, 120_000L, 30_000L, null);
        service = new EntityExtractionService(canonicalizationService, embeddingService,
                chatClientResolver, entityMapper, eventMapper, chunkEntityMapper,
                documentMapper, vectorStoreMapper, deriveDebouncer, new DefaultScopedTasks(),
                properties);
    }

    private static VectorStoreMapper.VectorStoreRow row(String id) {
        return new VectorStoreMapper.VectorStoreRow(id, "content-" + id, null);
    }

    private void stubChatReturns(String json) {
        when(chatClientResolver.resolve()).thenReturn(chatCapable);
        when(chatCapable.chat(any())).thenReturn(new LlmResponse(json, false, null, null, null, null));
    }

    @Nested
    @DisplayName("onEtlVectorized 事件监听器")
    class EventListenerTests {

        @Test
        @DisplayName("onEtlVectorized 委托 extractAndIndex（无 chunk 退出路径写完成标记，§6.2）")
        void onEtlVectorizedDelegates() {
            when(vectorStoreMapper.selectChunksByDocumentId(anyString()))
                    .thenReturn(List.of());

            service.onEtlVectorized(new EtlVectorizedEvent(1L, 100L, null));

            verify(vectorStoreMapper).selectChunksByDocumentId("1");
            verify(documentMapper).markEntityExtracted(1L);
        }
    }

    @Nested
    @DisplayName("完成标记与门控（§6.1/§6.2）")
    class MarkerAndGatingTests {

        @Test
        @DisplayName("无 chunk → 不抽取，写完成标记")
        void noChunks_marksExtracted() {
            when(vectorStoreMapper.selectChunksByDocumentId(anyString()))
                    .thenReturn(List.of());

            service.extractAndIndex(1L, 100L, null);

            verify(canonicalizationService, never())
                    .aggregateAndUpsert(anyList(), anyLong(), any(), anyLong());
            verify(documentMapper).markEntityExtracted(1L);
        }

        @Test
        @DisplayName("graphChanged=false（纯重投递）→ 不提交 derive（验证 #14），仍 embedding + 标记")
        void graphChangedFalse_skipsDerive() {
            stubHappyPath(new AggregateResult(List.of(1L, 2L), false));

            service.extractAndIndex(1L, 100L, null);

            verify(deriveDebouncer, never()).submit(anyLong(), any());
            verify(embeddingService).embedEntities(anyList());
            verify(documentMapper).markEntityExtracted(1L);
        }

        @Test
        @DisplayName("graphChanged=true → 经防抖提交 derive")
        void graphChangedTrue_submitsDerive() {
            stubHappyPath(new AggregateResult(List.of(1L, 2L), true));

            service.extractAndIndex(1L, 100L, null);

            verify(deriveDebouncer).submit(100L, null);
            verify(documentMapper).markEntityExtracted(1L);
        }

        @Test
        @DisplayName("aggregateAndUpsert 传 documentId（V30 链接权威归属）")
        void passesDocumentId() {
            stubHappyPath(new AggregateResult(List.of(1L), false));

            service.extractAndIndex(42L, 100L, null);

            verify(canonicalizationService).aggregateAndUpsert(anyList(), eq(100L), eq(null), eq(42L));
        }

        @Test
        @DisplayName("抽取异常 → 完成标记不写（留待 §6.2 次日重链接，验证 #15 语义）")
        void failure_noMarker() {
            when(vectorStoreMapper.selectChunksByDocumentId(anyString()))
                    .thenThrow(new RuntimeException("vector store down"));

            service.extractAndIndex(1L, 100L, null);   // 外层 catch 吞掉

            verify(documentMapper, never()).markEntityExtracted(anyLong());
        }

        private void stubHappyPath(AggregateResult result) {
            when(vectorStoreMapper.selectChunksByDocumentId(anyString()))
                    .thenReturn(List.of(row("c1")));
            stubChatReturns("""
                    {"event": "e", "entities": [{"name": "PostgreSQL", "description": "db", "type": "product"}]}
                    """);
            when(canonicalizationService.aggregateAndUpsert(anyList(), anyLong(), any(), anyLong()))
                    .thenReturn(result);
            when(entityMapper.selectByIds(anyList())).thenReturn(List.of());
        }
    }

    @Nested
    @DisplayName("增量过滤与 event 结果保留")
    class IncrementalFilterTests {

        @Test
        @DisplayName("全部 chunk 已完成（events ∪ links 双源命中）→ 直接标记完成，零 LLM 调用")
        void allChunksDone_skipsExtraction() {
            when(vectorStoreMapper.selectChunksByDocumentId(anyString()))
                    .thenReturn(List.of(row("c1"), row("c2")));
            when(eventMapper.selectChunkIdsByDocumentId(1L)).thenReturn(List.of("c1"));
            when(chunkEntityMapper.selectExistingChunkIds(anyList())).thenReturn(List.of("c2"));

            service.extractAndIndex(1L, 100L, null);

            verify(chatClientResolver, never()).resolve();
            verify(canonicalizationService, never())
                    .aggregateAndUpsert(anyList(), anyLong(), any(), anyLong());
            verify(documentMapper).markEntityExtracted(1L);
        }

        @Test
        @DisplayName("event-only 抽取结果不再被丢弃：无实体但有 event 的 chunk 写入 rag_event（bugfix 回归）")
        void eventOnlyExtraction_kept() {
            when(vectorStoreMapper.selectChunksByDocumentId(anyString()))
                    .thenReturn(List.of(row("c1")));
            // 双源均未命中（首抽）
            when(canonicalizationService.aggregateAndUpsert(anyList(), anyLong(), any(), anyLong()))
                    .thenReturn(new AggregateResult(List.of(), false));
            stubChatReturns("{\"event\": \"only-event\", \"entities\": []}");

            service.extractAndIndex(1L, 100L, null);

            org.mockito.ArgumentCaptor<List<RagEvent>> captor =
                    org.mockito.ArgumentCaptor.forClass((Class) List.class);
            verify(eventMapper).insertIgnoreBatch(captor.capture());
            assertThat(captor.getValue())
                    .hasSize(1)
                    .first()
                    .extracting(RagEvent::getChunkId)
                    .isEqualTo("c1");
            verify(documentMapper).markEntityExtracted(1L);
        }
    }
}
