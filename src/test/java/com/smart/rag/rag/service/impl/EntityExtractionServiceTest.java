package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.rag.entity.RagEntity;
import com.smart.rag.rag.event.EtlVectorizedEvent;
import com.smart.rag.rag.mapper.EntityMapper;
import com.smart.rag.rag.mapper.EventMapper;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EntityExtractionService 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntityExtractionService 单元测试")
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
    private VectorStoreMapper vectorStoreMapper;
    @Mock
    private LlmClientRegistry llmClientRegistry;
    @Mock
    private ChatCapable chatCapable;
    @Mock
    private ExecutorService etlCpuExecutor;

    @InjectMocks
    private EntityExtractionService service;

    private static final String VALID_LLM_RESPONSE = """
            {
              "event": " discusses vector database technology",
              "entities": [
                {"name": "PostgreSQL", "description": "关系型数据库", "type": "product"},
                {"name": "pgvector", "description": "向量扩展", "type": "product"}
              ]
            }
            """;

    @Nested
    @DisplayName("onEtlVectorized 事件监听器")
    class EventListenerTests {

        @Test
        @DisplayName("onEtlVectorized 委托 extractAndIndex")
        void onEtlVectorizedDelegates() {
            when(vectorStoreMapper.selectChunksByDocumentId(anyString()))
                    .thenReturn(List.of());

            service.onEtlVectorized(new EtlVectorizedEvent(1L, 100L, null));

            verify(vectorStoreMapper).selectChunksByDocumentId("1");
        }

        @Test
        @DisplayName("onEtlVectorized with teamId")
        void onEtlVectorizedWithTeamDelegates() {
            when(vectorStoreMapper.selectChunksByDocumentId(anyString()))
                    .thenReturn(List.of());

            service.onEtlVectorized(new EtlVectorizedEvent(2L, 200L, 300L));

            verify(vectorStoreMapper).selectChunksByDocumentId("2");
        }
    }

    @Nested
    @DisplayName("Failure isolation — 单 chunk 失败不阻塞其他 chunk")
    class FailureIsolationTests {

        @Test
        @DisplayName("无 chunk 时跳过（无异常抛出）")
        void noChunksSkipped() {
            when(vectorStoreMapper.selectChunksByDocumentId(anyString()))
                    .thenReturn(List.of());

            // 不抛异常
            service.extractAndIndex(1L, 100L, null);

            verify(canonicalizationService, never()).aggregateAndUpsert(anyList(), anyLong(), any());
        }
    }
}
