package com.smart.rag.rag.service.impl;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.EmbeddingCapable;
import com.smart.rag.infrastructure.llm.EmbeddingType;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.entity.RagEntity;
import com.smart.rag.rag.mapper.EntityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EntityEmbeddingService 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntityEmbeddingService 单元测试")
class EntityEmbeddingServiceTest {

    @Mock
    private LlmClientRegistry llmClientRegistry;
    @Mock
    private EntityMapper entityMapper;
    @Mock
    private EmbeddingCapable embeddingCapable;
    @Mock
    private ChatCapable chatCapable;
    @Mock
    private ScopeLockTemplate scopeLockTemplate;
    @Mock
    private LockRetryExecutor lockRetryExecutor;
    @Mock
    private TransactionTemplate transactionTemplate;

    private RagEntityProperties properties;

    @InjectMocks
    private EntityEmbeddingService service;

    @BeforeEach
    void setUp() {
        properties = new RagEntityProperties(10, 500, 0.85, 50, 20, 10, 1, 0.7, 0.5, 0.3, 0.2, true, null, true, 0, 0, 0, 0, null);
        service = new EntityEmbeddingService(llmClientRegistry, entityMapper, properties,
                scopeLockTemplate, lockRetryExecutor, transactionTemplate);

        // 写回经 advisory 短事务（V30 §3.2.1）：替身直接执行临界区
        lenient().doAnswer(invocation -> {
            Runnable body = invocation.getArgument(2);
            body.run();
            return null;
        }).when(scopeLockTemplate).withinScopeLock(any(), any(), any());
        lenient().doAnswer(invocation -> {
            Runnable body = invocation.getArgument(0);
            body.run();
            return null;
        }).when(lockRetryExecutor).execute(any(Runnable.class));
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    // ==================== embedEntities ====================

    @Nested
    @DisplayName("embedEntities — 批量 embedding")
    class EmbedEntitiesTests {

        @Test
        @DisplayName("已有 embedding 的实体仍被重新嵌入")
        void reEmbedAlreadyEmbedded() {
            when(llmClientRegistry.getDefault(LlmCapability.EMBEDDING, EmbeddingCapable.class))
                    .thenReturn(embeddingCapable);
            when(embeddingCapable.embedBatch(anyList(), eq(EmbeddingType.DOCUMENT)))
                    .thenReturn(List.of(new float[]{0.9f, 0.1f}));

            RagEntity entity = new RagEntity();
            entity.setId(1L);
            entity.setDescription("updated description");
            entity.setEmbedding(new float[]{0.1f, 0.2f});

            service.embedEntities(List.of(entity));

            verify(embeddingCapable).embedBatch(anyList(), eq(EmbeddingType.DOCUMENT));
            verify(entityMapper).updateEmbeddingBatch(argThat(items -> items.size() == 1 && items.get(0).id() == 1L));
        }

        @Test
        @DisplayName("空 description 被跳过")
        void skipEmptyDescription() {
            RagEntity entity = new RagEntity();
            entity.setId(1L);
            entity.setDescription(null);

            service.embedEntities(List.of(entity));

            verify(llmClientRegistry, never()).getDefault(any(), any());
        }

        @Test
        @DisplayName("正常实体分批 embed 并更新")
        void batchEmbedAnd() {
            when(llmClientRegistry.getDefault(LlmCapability.EMBEDDING, EmbeddingCapable.class))
                    .thenReturn(embeddingCapable);
            when(embeddingCapable.embedBatch(anyList(), eq(EmbeddingType.DOCUMENT)))
                    .thenReturn(List.of(new float[]{0.5f, 0.5f}, new float[]{0.3f, 0.7f}));
            RagEntity entity1 = new RagEntity();
            entity1.setId(1L);
            entity1.setDescription("desc1");

            RagEntity entity2 = new RagEntity();
            entity2.setId(2L);
            entity2.setDescription("desc2");

            service.embedEntities(List.of(entity1, entity2));

            verify(embeddingCapable).embedBatch(anyList(), eq(EmbeddingType.DOCUMENT));
            verify(entityMapper).updateEmbeddingBatch(argThat(items -> items.size() == 2
                    && items.get(0).id() == 1L && items.get(1).id() == 2L));
        }

        @Test
        @DisplayName("空列表无操作")
        void emptyList() {
            service.embedEntities(List.of());
            service.embedEntities(null);

            verify(llmClientRegistry, never()).getDefault(any(), any());
        }

        @Test
        @DisplayName("写回在 scope advisory 短事务内执行（V30 §3.2.1 收编，锁键 = 实体 scope）")
        void writeBackWithinScopeLock() {
            when(llmClientRegistry.getDefault(LlmCapability.EMBEDDING, EmbeddingCapable.class))
                    .thenReturn(embeddingCapable);
            when(embeddingCapable.embedBatch(anyList(), eq(EmbeddingType.DOCUMENT)))
                    .thenReturn(List.of(new float[]{0.5f, 0.5f}));
            RagEntity entity = new RagEntity();
            entity.setId(1L);
            entity.setUserId(100L);
            entity.setTeamId(20L);
            entity.setDescription("desc");

            service.embedEntities(List.of(entity));

            verify(scopeLockTemplate).withinScopeLock(eq(100L), eq(20L), any());
        }

        @Test
        @DisplayName("写回重试耗尽 → 异常传播（extractAndIndex 异常退出 → 标记不写 → §6.2 次日重链接）")
        void writeBackExhaustion_propagates() {
            when(llmClientRegistry.getDefault(LlmCapability.EMBEDDING, EmbeddingCapable.class))
                    .thenReturn(embeddingCapable);
            when(embeddingCapable.embedBatch(anyList(), eq(EmbeddingType.DOCUMENT)))
                    .thenReturn(List.of(new float[]{0.5f, 0.5f}));
            doThrow(new RuntimeException("55P03 exhausted"))
                    .when(lockRetryExecutor).execute(any(Runnable.class));
            RagEntity entity = new RagEntity();
            entity.setId(1L);
            entity.setUserId(100L);
            entity.setDescription("desc");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.embedEntities(List.of(entity)))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("description 超 500 字符时尝试 LLM 压缩")
        void longDescriptionCompress() {
            when(llmClientRegistry.getDefault(LlmCapability.CHAT, ChatCapable.class))
                    .thenReturn(chatCapable);
            when(llmClientRegistry.getDefault(LlmCapability.EMBEDDING, EmbeddingCapable.class))
                    .thenReturn(embeddingCapable);
            when(chatCapable.chat(any(ChatRequest.class)))
                    .thenReturn(new LlmResponse("compressed", false, null, List.of(), java.util.Map.of()));
            when(embeddingCapable.embedBatch(anyList(), eq(EmbeddingType.DOCUMENT)))
                    .thenReturn(List.of(new float[]{0.3f, 0.7f}));

            properties = new RagEntityProperties(10, 10, 0.85, 50, 20, 10, 1, 0.7, 0.5, 0.3, 0.2, true, null, true, 0, 0, 0, 0, null);
            service = new EntityEmbeddingService(llmClientRegistry, entityMapper, properties,
                    scopeLockTemplate, lockRetryExecutor, transactionTemplate);

            RagEntity entity = new RagEntity();
            entity.setId(1L);
            entity.setDescription("This is a very long description that exceeds the max length threshold for embedding");

            service.embedEntities(List.of(entity));

            // 验证 ChatCapable 被调用进行压缩
            verify(chatCapable).chat(any(ChatRequest.class));
            verify(entityMapper).updateEmbeddingBatch(argThat(items -> items.size() == 1 && items.get(0).id() == 1L));
        }
    }
}
