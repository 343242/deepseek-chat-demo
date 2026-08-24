package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.Subtask;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.infrastructure.concurrent.TaskState;
import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.rag.event.EtlVectorizedEvent;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EventMapper;
import com.smart.rag.rag.mapper.RagDocumentMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.service.impl.EntityCanonicalizationService.AggregateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EntityExtractionService 单元测试（V30：graphChanged 门控 + 完成标记）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntityExtractionService 单元测试（V30）")
class EntityExtractionServiceTest {

    @Mock
    private EntityCanonicalizationService canonicalizationService;
    @Mock
    private EntityEmbeddingService embeddingService;
    @Mock
    private EntityMapper entityMapper;
    @Mock
    private EventMapper eventMapper;
    @Mock
    private RagDocumentMapper documentMapper;
    @Mock
    private VectorStoreMapper vectorStoreMapper;
    @Mock
    private LlmClientRegistry llmClientRegistry;
    @Mock
    private ChatCapable chatCapable;
    @Mock
    private ExecutorService etlCpuExecutor;
    @Mock
    private ScopedTasks scopedTasks;
    @Mock
    private DeriveDebouncer deriveDebouncer;

    @InjectMocks
    private EntityExtractionService service;

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
            when(vectorStoreMapper.selectChunksByDocumentId(anyString())).thenReturn(List.of(
                    new VectorStoreMapper.VectorStoreRow("c1", "content-1", null)));
            when(llmClientRegistry.getDefault(eq(LlmCapability.CHAT), eq(ChatCapable.class)))
                    .thenReturn(chatCapable);
            when(chatCapable.chat(any())).thenReturn(new com.smart.rag.infrastructure.llm.LlmResponse(
                    """
                    {"event": "e", "entities": [{"name": "PostgreSQL", "description": "db", "type": "product"}]}
                    """,
                    false, null, null, null, null));
            when(canonicalizationService.aggregateAndUpsert(anyList(), anyLong(), any(), anyLong()))
                    .thenReturn(result);
            when(entityMapper.selectByIds(anyList())).thenReturn(List.of());
            // ScopedTasks 替身：fork 同步执行（结构化并发行为不在本测试范围）
            when(scopedTasks.open(anyString(), any(ScopeOptions.class), any(ExecutorService.class)))
                    .thenReturn(new SyncTaskScope());
        }

        /** 同步执行 fork 的 TaskScope 替身。 */
        private static final class SyncTaskScope implements TaskScope {
            private final List<Subtask<?>> subs = new ArrayList<>();

            @Override
            public <T> Subtask<T> fork(String name, Callable<T> task) {
                try {
                    T value = task.call();
                    Subtask<T> sub = new CompletedSubtask<>(value, null);
                    subs.add(sub);
                    return sub;
                } catch (Exception e) {
                    Subtask<T> sub = new CompletedSubtask<>(null, e);
                    subs.add(sub);
                    return sub;
                }
            }

            @Override
            public void join() {}

            @Override
            public void joinUntil(Duration timeout) {}

            @Override
            public boolean cancel(Subtask<?> subtask) { return false; }

            @Override
            public void throwIfFailed() {}

            @Override
            public List<Subtask<?>> subtasks() { return subs; }

            @Override
            public void close() {}
        }

        private record CompletedSubtask<T>(T value, Throwable error) implements Subtask<T> {
            @Override public String name() { return "sub"; }
            @Override public TaskState state() { return error == null ? TaskState.SUCCESS : TaskState.FAILED; }
            @Override public T result() { return value; }
            @Override public Throwable exception() { return error; }
        }
    }
}
