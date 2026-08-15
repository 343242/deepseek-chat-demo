package com.smart.rag.infrastructure.llm.client.bailian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.llm.EmbeddingCandidate;
import com.smart.rag.infrastructure.llm.EmbeddingType;
import com.smart.rag.infrastructure.exception.RemoteException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * BailianEmbeddingClient 单元测试
 * <p>
 * 仅覆盖无 HTTP 依赖的逻辑：批量上限解析（候选 params.batch-size）、空输入与空白文本防御、
 * 响应 text_index 归位契约。
 */
@DisplayName("BailianEmbeddingClient 单元测试")
class BailianEmbeddingClientTest {

    private static BailianEmbeddingClient newClient(Map<String, Object> params) {
        EmbeddingCandidate candidate = new EmbeddingCandidate();
        candidate.setId("test-emb");
        candidate.setProvider("bailian");
        candidate.setModel("text-embedding-v4");
        candidate.setDimension(8);
        candidate.setPriority(1);
        candidate.setParams(params);
        return new BailianEmbeddingClient("http://localhost:1", "/embeddings", "test-key",
            candidate, mock(ScopedTasks.class));
    }

    @Nested
    @DisplayName("resolveBatchSize — 候选 params.batch-size 解析")
    class ResolveBatchSize {

        @Test
        @DisplayName("未声明 params 时取默认 10（v3/v4 官方上限）")
        void defaultsToTen() {
            assertThat(BailianEmbeddingClient.resolveBatchSize(null)).isEqualTo(10);
            assertThat(BailianEmbeddingClient.resolveBatchSize(Map.of())).isEqualTo(10);
        }

        @Test
        @DisplayName("数字类型的 batch-size 生效（qwen3.7-text-embedding 官方上限 20）")
        void parsesNumber() {
            assertThat(BailianEmbeddingClient.resolveBatchSize(Map.of("batch-size", 20))).isEqualTo(20);
        }

        @Test
        @DisplayName("字符串类型的 batch-size 生效（YAML 松散绑定场景）")
        void parsesString() {
            assertThat(BailianEmbeddingClient.resolveBatchSize(Map.of("batch-size", "25"))).isEqualTo(25);
        }

        @Test
        @DisplayName("非法值（非正数、无法解析）回退默认 10")
        void fallsBackOnInvalidValue() {
            assertThat(BailianEmbeddingClient.resolveBatchSize(Map.of("batch-size", 0))).isEqualTo(10);
            assertThat(BailianEmbeddingClient.resolveBatchSize(Map.of("batch-size", -5))).isEqualTo(10);
            assertThat(BailianEmbeddingClient.resolveBatchSize(Map.of("batch-size", "abc"))).isEqualTo(10);
            assertThat(BailianEmbeddingClient.resolveBatchSize(Map.of("batch-size", true))).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("防御性输入处理")
    class DefensiveInput {

        @Test
        @DisplayName("embedBatch 空输入返回空列表且不发请求")
        void embedBatchEmptyInput() {
            BailianEmbeddingClient client = newClient(Map.of());

            assertThat(client.embedBatch(null, EmbeddingType.DOCUMENT)).isEmpty();
            assertThat(client.embedBatch(List.of(), EmbeddingType.DOCUMENT)).isEmpty();
        }

        @Test
        @DisplayName("空白文本返回维度长度的零向量拷贝")
        void blankTextReturnsZeroVectorCopy() {
            BailianEmbeddingClient client = newClient(Map.of());

            float[] first = client.embed("   ", EmbeddingType.QUERY);
            float[] second = client.embed(null, EmbeddingType.DOCUMENT);

            assertThat(first).hasSize(8).containsOnly(0.0f);
            assertThat(second).hasSize(8).containsOnly(0.0f);
            assertThat(first).isNotSameAs(second);
        }
    }

    @Nested
    @DisplayName("extractAll — text_index 归位契约（官方响应 schema）")
    class ExtractAllByTextIndex {

        private final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("顺序响应按输入顺序返回向量")
        void orderedResponse() throws Exception {
            JsonNode response = objectMapper.readTree("""
                {"output":{"embeddings":[
                  {"text_index":0,"embedding":[0.1]},
                  {"text_index":1,"embedding":[0.2]}
                ]}}""");

            float[][] result = newClient(Map.of()).extractAll(response);

            assertThat(result).hasDimensions(2, 1);
            assertThat(result[0]).containsExactly(0.1f);
            assertThat(result[1]).containsExactly(0.2f);
        }

        @Test
        @DisplayName("乱序响应按 text_index 归位，防止向量与文本错配")
        void outOfOrderMappedByTextIndex() throws Exception {
            JsonNode response = objectMapper.readTree("""
                {"output":{"embeddings":[
                  {"text_index":1,"embedding":[0.2]},
                  {"text_index":0,"embedding":[0.1]}
                ]}}""");

            float[][] result = newClient(Map.of()).extractAll(response);

            assertThat(result[0]).containsExactly(0.1f);
            assertThat(result[1]).containsExactly(0.2f);
        }

        @Test
        @DisplayName("text_index 缺失时按数组位置回退")
        void missingTextIndexFallsBackToPosition() throws Exception {
            JsonNode response = objectMapper.readTree("""
                {"output":{"embeddings":[
                  {"embedding":[0.3]},
                  {"embedding":[0.4]}
                ]}}""");

            float[][] result = newClient(Map.of()).extractAll(response);

            assertThat(result[0]).containsExactly(0.3f);
            assertThat(result[1]).containsExactly(0.4f);
        }

        @Test
        @DisplayName("text_index 越界时按数组位置回退")
        void outOfRangeTextIndexFallsBackToPosition() throws Exception {
            JsonNode response = objectMapper.readTree("""
                {"output":{"embeddings":[
                  {"text_index":7,"embedding":[0.5]},
                  {"text_index":1,"embedding":[0.6]}
                ]}}""");

            float[][] result = newClient(Map.of()).extractAll(response);

            assertThat(result[0]).containsExactly(0.5f);
            assertThat(result[1]).containsExactly(0.6f);
        }

        @Test
        @DisplayName("重复 text_index 导致槽位缺失时抛解析异常而非静默错配")
        void duplicateTextIndexThrowsParseError() throws Exception {
            JsonNode response = objectMapper.readTree("""
                {"output":{"embeddings":[
                  {"text_index":0,"embedding":[0.1]},
                  {"text_index":0,"embedding":[0.9]}
                ]}}""");

            assertThatThrownBy(() -> newClient(Map.of()).extractAll(response))
                .isInstanceOf(RemoteException.class)
                .hasMessageContaining("text_index misaligned");
        }
    }
}
