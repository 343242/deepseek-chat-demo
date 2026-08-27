package com.smart.rag.rag.retrieval.entity;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.rag.config.RagEntityProperties;
import com.smart.rag.rag.service.impl.EntityChatClientResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link EntitySeedExtractor} 单元测试。
 * <p>
 * Mock {@link ChatCapable}，验证 JSON 数组解析 + LLM 失败隔离（返回空列表，不阻塞 query）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EntitySeedExtractor — query → seed entities")
class EntitySeedExtractorTest {

    @Mock
    private EntityChatClientResolver chatClientResolver;
    @Mock
    private ChatCapable chatCapable;

    private RagEntityProperties properties;
    private EntitySeedExtractor extractor;

    @BeforeEach
    void setUp() {
        properties = new RagEntityProperties(20, 500, 32, 0.85, 50, 20, 10, 1, 0.7, 0.5, 0.3, 0.2, true, null, true, 0, 0, 0, 0, null);
        extractor = new EntitySeedExtractor(chatClientResolver, properties);
    }

    @Nested
    @DisplayName("正常路径")
    class HappyPath {

        @Test
        @DisplayName("LLM 返回 JSON 数组 → 解析为 seed 实体列表")
        void extract_validJsonArray_parsed() {
            when(chatClientResolver.resolve()).thenReturn(chatCapable);
            when(chatCapable.chat(any(ChatRequest.class)))
                    .thenReturn(new LlmResponse("[\"PostgreSQL\", \"向量检索\", \"pgvector\"]", false, null, List.of(), Map.of()));

            List<String> seeds = extractor.extract("PostgreSQL 向量检索方案");

            assertThat(seeds).containsExactly("PostgreSQL", "向量检索", "pgvector");
        }

        @Test
        @DisplayName("LLM 返回空数组 → 空列表")
        void extract_emptyArray_empty() {
            when(chatClientResolver.resolve()).thenReturn(chatCapable);
            when(chatCapable.chat(any(ChatRequest.class)))
                    .thenReturn(new LlmResponse("[]", false, null, List.of(), Map.of()));

            List<String> seeds = extractor.extract("无实体的查询");

            assertThat(seeds).isEmpty();
        }
    }

    @Nested
    @DisplayName("失败隔离（§8.3）")
    class FailureIsolation {

        @Test
        @DisplayName("LLM 抛异常 → 返回空列表，不向上传播")
        void extract_llmThrows_returnsEmpty() {
            when(chatClientResolver.resolve()).thenReturn(chatCapable);
            when(chatCapable.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM timeout"));

            List<String> seeds = extractor.extract("查询");

            assertThat(seeds).isEmpty();
        }

        @Test
        @DisplayName("extraction-model 解析失败（fail-fast 约定）→ 异常被失败隔离吞掉，返回空列表")
        void extract_resolverThrows_returnsEmpty() {
            when(chatClientResolver.resolve()).thenThrow(new RemoteException(
                    RemoteErrorCode.LLM_CONFIG_ERROR, "候选 ID 无效"));

            List<String> seeds = extractor.extract("查询");

            assertThat(seeds).isEmpty();
        }

        @Test
        @DisplayName("LLM 返回非 JSON → 返回空列表")
        void extract_invalidJson_returnsEmpty() {
            when(chatClientResolver.resolve()).thenReturn(chatCapable);
            when(chatCapable.chat(any(ChatRequest.class)))
                    .thenReturn(new LlmResponse("这不是JSON", false, null, List.of(), Map.of()));

            List<String> seeds = extractor.extract("查询");

            assertThat(seeds).isEmpty();
        }

        @Test
        @DisplayName("LLM 返回 null content → 空列表")
        void extract_nullContent_empty() {
            when(chatClientResolver.resolve()).thenReturn(chatCapable);
            when(chatCapable.chat(any(ChatRequest.class)))
                    .thenReturn(new LlmResponse(null, false, null, List.of(), Map.of()));

            List<String> seeds = extractor.extract("查询");

            assertThat(seeds).isEmpty();
        }
    }
}
