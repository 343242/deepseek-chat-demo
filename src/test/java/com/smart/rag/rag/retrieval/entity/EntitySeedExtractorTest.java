package com.smart.rag.rag.retrieval.entity;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.ChatRequest;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.LlmResponse;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.rag.config.RagEntityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
    private LlmClientRegistry llmClientRegistry;
    @Mock
    private ChatCapable chatCapable;

    private RagEntityProperties properties;
    private EntitySeedExtractor extractor;

    @BeforeEach
    void setUp() {
        properties = new RagEntityProperties(10, 500, 0.85, 50, 20, 10, 1, 0.7, 0.5, 0.3, 0.2, true, null, true);
        extractor = new EntitySeedExtractor(llmClientRegistry, properties);
    }

    @Nested
    @DisplayName("正常路径")
    class HappyPath {

        @Test
        @DisplayName("LLM 返回 JSON 数组 → 解析为 seed 实体列表")
        void extract_validJsonArray_parsed() {
            when(llmClientRegistry.getDefault(LlmCapability.CHAT, ChatCapable.class)).thenReturn(chatCapable);
            when(chatCapable.chat(any(ChatRequest.class)))
                    .thenReturn(new LlmResponse("[\"PostgreSQL\", \"向量检索\", \"pgvector\"]", false, null, List.of(), Map.of()));

            List<String> seeds = extractor.extract("PostgreSQL 向量检索方案");

            assertThat(seeds).containsExactly("PostgreSQL", "向量检索", "pgvector");
        }

        @Test
        @DisplayName("LLM 返回空数组 → 空列表")
        void extract_emptyArray_empty() {
            when(llmClientRegistry.getDefault(LlmCapability.CHAT, ChatCapable.class)).thenReturn(chatCapable);
            when(chatCapable.chat(any(ChatRequest.class)))
                    .thenReturn(new LlmResponse("[]", false, null, List.of(), Map.of()));

            List<String> seeds = extractor.extract("无实体的查询");

            assertThat(seeds).isEmpty();
        }

        @Test
        @DisplayName("extractionModel 非空 → 用 get(candidateId) 而非 getDefault")
        void extract_customModel_usesGetById() {
            properties = new RagEntityProperties(10, 500, 0.85, 50, 20, 10, 1, 0.7, 0.5, 0.3, 0.2, true, "deepseek-v4-flash", true);
            extractor = new EntitySeedExtractor(llmClientRegistry, properties);

            when(llmClientRegistry.get(eq("deepseek-v4-flash"), eq(ChatCapable.class))).thenReturn(chatCapable);
            when(chatCapable.chat(any(ChatRequest.class)))
                    .thenReturn(new LlmResponse("[\"MySQL\"]", false, null, List.of(), Map.of()));

            List<String> seeds = extractor.extract("MySQL 全文检索");

            assertThat(seeds).containsExactly("MySQL");
        }
    }

    @Nested
    @DisplayName("失败隔离（§8.3）")
    class FailureIsolation {

        @Test
        @DisplayName("LLM 抛异常 → 返回空列表，不向上传播")
        void extract_llmThrows_returnsEmpty() {
            when(llmClientRegistry.getDefault(LlmCapability.CHAT, ChatCapable.class)).thenReturn(chatCapable);
            when(chatCapable.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM timeout"));

            List<String> seeds = extractor.extract("查询");

            assertThat(seeds).isEmpty();
        }

        @Test
        @DisplayName("LLM 返回非 JSON → 返回空列表")
        void extract_invalidJson_returnsEmpty() {
            when(llmClientRegistry.getDefault(LlmCapability.CHAT, ChatCapable.class)).thenReturn(chatCapable);
            when(chatCapable.chat(any(ChatRequest.class)))
                    .thenReturn(new LlmResponse("这不是JSON", false, null, List.of(), Map.of()));

            List<String> seeds = extractor.extract("查询");

            assertThat(seeds).isEmpty();
        }

        @Test
        @DisplayName("LLM 返回 null content → 空列表")
        void extract_nullContent_empty() {
            when(llmClientRegistry.getDefault(LlmCapability.CHAT, ChatCapable.class)).thenReturn(chatCapable);
            when(chatCapable.chat(any(ChatRequest.class)))
                    .thenReturn(new LlmResponse(null, false, null, List.of(), Map.of()));

            List<String> seeds = extractor.extract("查询");

            assertThat(seeds).isEmpty();
        }
    }
}
