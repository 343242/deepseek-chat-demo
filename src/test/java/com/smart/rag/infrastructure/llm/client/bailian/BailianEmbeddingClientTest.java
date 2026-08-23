package com.smart.rag.infrastructure.llm.client.bailian;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.smart.rag.infrastructure.concurrent.DefaultScopedTasks;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.llm.EmbeddingCandidate;
import com.smart.rag.infrastructure.llm.EmbeddingType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BailianEmbeddingClient 单元测试（SDK 实现）
 * <p>
 * 覆盖：批量上限解析（params.batch-size）、空输入与空白文本防御（零向量兜底）、
 * SDK 参数映射（text_type/instruct 配套/dimension/output_type）、响应 text_index
 * 归位契约、大批量并发分批一致性。桩注入 SDK facade。
 */
@DisplayName("BailianEmbeddingClient 单元测试（SDK）")
class BailianEmbeddingClientTest {

    private static EmbeddingCandidate candidate(Map<String, Object> params) {
        EmbeddingCandidate c = new EmbeddingCandidate();
        c.setId("test-emb");
        c.setProvider("bailian");
        c.setModel("text-embedding-v4");
        c.setDimension(8);
        c.setPriority(1);
        c.setParams(params);
        return c;
    }

    private static BailianEmbeddingClient newClient(Map<String, Object> params, TextEmbedding facade) {
        // 真实 ScopedTasks（并发分批路径需要 open/fork 真实现；mock 会返回 null scope）
        return new BailianEmbeddingClient("test-key", candidate(params), new DefaultScopedTasks(), facade);
    }

    private static TextEmbeddingResultItem item(int textIndex, double... values) {
        TextEmbeddingResultItem item = new TextEmbeddingResultItem();
        item.setTextIndex(textIndex);
        List<Double> embedding = new ArrayList<>(values.length);
        for (double v : values) embedding.add(v);
        item.setEmbedding(embedding);
        return item;
    }

    private static TextEmbeddingResult resultOf(TextEmbeddingResultItem... items) {
        TextEmbeddingResult result = mock(TextEmbeddingResult.class);
        TextEmbeddingOutput output = mock(TextEmbeddingOutput.class);
        when(output.getEmbeddings()).thenReturn(List.of(items));
        when(result.getOutput()).thenReturn(output);
        return result;
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
            assertThat(BailianEmbeddingClient.resolveBatchSize(Map.of("batch-size", "20"))).isEqualTo(20);
        }

        @Test
        @DisplayName("非法值回退默认（非正数/无法解析）")
        void fallsBackOnInvalid() {
            assertThat(BailianEmbeddingClient.resolveBatchSize(Map.of("batch-size", 0))).isEqualTo(10);
            assertThat(BailianEmbeddingClient.resolveBatchSize(Map.of("batch-size", "abc"))).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("SDK 参数映射")
    class ParamMapping {

        @Test
        @DisplayName("DOCUMENT 编码：text_type=document、无 instruct、dimension/output_type 透传")
        void documentParams() throws Exception {
            TextEmbedding facade = mock(TextEmbedding.class);
            // 夹具先构造（含内部 mock），再进入 when() 打桩——嵌套 mock 会触发 UnfinishedStubbing
            TextEmbeddingResult result = resultOf(item(0, 1, 2, 3));
            when(facade.call(any(TextEmbeddingParam.class))).thenReturn(result);

            BailianEmbeddingClient client = newClient(Map.of(), facade);
            client.embed("文本", EmbeddingType.DOCUMENT);

            ArgumentCaptor<TextEmbeddingParam> captor = ArgumentCaptor.forClass(TextEmbeddingParam.class);
            verify(facade).call(captor.capture());
            TextEmbeddingParam param = captor.getValue();
            assertThat(param.getModel()).isEqualTo("text-embedding-v4");
            // TextEmbeddingParam 字段无 getter（非 @Data），经 getParameters() map 断言
            assertThat(param.getParameters())
                .containsEntry("text_type", "document")
                .containsEntry("output_type", "dense")
                .containsEntry("dimension", 8)
                .doesNotContainKey("instruct"); // instruct 仅 query 侧配套
            var texts = param.getInput().getAsJsonArray("texts");
            assertThat(texts.size()).isEqualTo(1);
            assertThat(texts.get(0).getAsString()).isEqualTo("文本");
        }

        @Test
        @DisplayName("QUERY 编码：text_type=query + instruct 配套（官方约束：instruct 须搭配 query）")
        void queryParams() throws Exception {
            TextEmbedding facade = mock(TextEmbedding.class);
            TextEmbeddingResult result = resultOf(item(0, 1, 2, 3));
            when(facade.call(any(TextEmbeddingParam.class))).thenReturn(result);

            BailianEmbeddingClient client = newClient(Map.of(), facade);
            client.embed("问题", EmbeddingType.QUERY);

            ArgumentCaptor<TextEmbeddingParam> captor = ArgumentCaptor.forClass(TextEmbeddingParam.class);
            verify(facade).call(captor.capture());
            assertThat(captor.getValue().getParameters().get("text_type")).isEqualTo("query");
            assertThat(captor.getValue().getParameters()).containsKey("instruct");
        }
    }

    @Nested
    @DisplayName("extractAll — text_index 归位契约（官方响应 schema）")
    class ExtractAll {

        @Test
        @DisplayName("乱序 text_index 按声明索引归位，向量不错配")
        void reordersByTextIndex() {
            BailianEmbeddingClient client = newClient(Map.of(), mock(TextEmbedding.class));
            TextEmbeddingOutput output = mock(TextEmbeddingOutput.class);
            when(output.getEmbeddings()).thenReturn(List.of(
                item(1, 4, 5, 6),
                item(0, 1, 2, 3)));

            float[][] vectors = client.extractAll(output);
            assertThat(vectors[0]).containsExactly(1f, 2f, 3f);
            assertThat(vectors[1]).containsExactly(4f, 5f, 6f);
        }

        @Test
        @DisplayName("text_index 越界回退数组顺序（防御乱序脏数据）")
        void outOfRangeFallsBackToPosition() {
            BailianEmbeddingClient client = newClient(Map.of(), mock(TextEmbedding.class));
            TextEmbeddingOutput output = mock(TextEmbeddingOutput.class);
            when(output.getEmbeddings()).thenReturn(List.of(item(99, 7, 8, 9)));

            assertThat(client.extractAll(output)[0]).containsExactly(7f, 8f, 9f);
        }

        @Test
        @DisplayName("空响应抛解析异常")
        void emptyResponseRejected() {
            BailianEmbeddingClient client = newClient(Map.of(), mock(TextEmbedding.class));
            TextEmbeddingOutput output = mock(TextEmbeddingOutput.class);
            when(output.getEmbeddings()).thenReturn(List.of());

            assertThatThrownBy(() -> client.extractAll(output))
                .isInstanceOf(RemoteException.class);
        }
    }

    @Nested
    @DisplayName("防御性输入与批量")
    class DefensiveInput {

        @Test
        @DisplayName("null/空白文本返回零向量（不触发 API）")
        void blankReturnsZeroVector() {
            TextEmbedding facade = mock(TextEmbedding.class);
            BailianEmbeddingClient client = newClient(Map.of(), facade);

            float[] zero = client.embed("  ", EmbeddingType.DOCUMENT);
            assertThat(zero).hasSize(8).containsOnly(0f);
            verifyNoInteractions(facade);
        }

        @Test
        @DisplayName("embedBatch 空列表返回空")
        void emptyBatch() {
            BailianEmbeddingClient client = newClient(Map.of(), mock(TextEmbedding.class));
            assertThat(client.embedBatch(List.of(), EmbeddingType.DOCUMENT)).isEmpty();
        }

        @Test
        @DisplayName("大批量按 batchSize 分片且结果对位（batch-size=2 × 5 条 → 3 批）")
        void largeBatchSplitAndAligned() throws Exception {
            TextEmbedding facade = mock(TextEmbedding.class);
            // 按收到的批内文本返回可区分的向量：v = [批内首文本序号, 0, 0]
            when(facade.call(any(TextEmbeddingParam.class))).thenAnswer(inv -> {
                TextEmbeddingParam p = inv.getArgument(0);
                List<TextEmbeddingResultItem> items = new ArrayList<>();
                var texts = p.getInput().getAsJsonArray("texts");
                int first = Integer.parseInt(texts.get(0).getAsString());
                for (int i = 0; i < texts.size(); i++) {
                    items.add(item(i, first, 0, 0));
                }
                return resultOf(items.toArray(TextEmbeddingResultItem[]::new));
            });

            BailianEmbeddingClient client = newClient(Map.of("batch-size", 2), facade);
            List<float[]> vectors = client.embedBatch(
                List.of("0", "1", "2", "3", "4"), EmbeddingType.DOCUMENT);

            assertThat(vectors).hasSize(5);
            // 对位校验：第 i 条向量的首元素 = 其所属批的首文本序号
            assertThat(vectors.get(0)[0]).isEqualTo(0);
            assertThat(vectors.get(1)[0]).isEqualTo(0);
            assertThat(vectors.get(2)[0]).isEqualTo(2);
            assertThat(vectors.get(3)[0]).isEqualTo(2);
            assertThat(vectors.get(4)[0]).isEqualTo(4);

            verify(facade, times(3)).call(any(TextEmbeddingParam.class));
        }

        @Test
        @DisplayName("单条 embed 返回首向量且维度透传")
        void singleEmbed() throws Exception {
            TextEmbedding facade = mock(TextEmbedding.class);
            TextEmbeddingResult result = resultOf(item(0, 0.5, 0.25));
            when(facade.call(any(TextEmbeddingParam.class))).thenReturn(result);

            BailianEmbeddingClient client = newClient(Map.of(), facade);
            assertThat(client.embed("x", EmbeddingType.QUERY)).containsExactly(0.5f, 0.25f);
            assertThat(client.dimension()).isEqualTo(8);
        }
    }
}
