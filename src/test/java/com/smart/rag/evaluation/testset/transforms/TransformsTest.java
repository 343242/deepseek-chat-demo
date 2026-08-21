package com.smart.rag.evaluation.testset.transforms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.testset.graph.Node;
import com.smart.rag.evaluation.testset.graph.RelationshipType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("transforms 包算法与抽取器")
class TransformsTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec spec;

    @Mock
    private ChatClient.CallResponseSpec response;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void stubFluentChain() {
        lenient().when(chatClient.prompt()).thenReturn(spec);
        lenient().when(spec.user(anyString())).thenReturn(spec);
        lenient().when(spec.call()).thenReturn(response);
    }

    private void llmReturns(String json) {
        when(response.content()).thenReturn(json);
    }

    @Nested
    @DisplayName("SummaryExtractor")
    class SummaryExtractorTest {

        @Test
        @DisplayName("正常解析 {text}；空返回/脏返回降级空串")
        void extractsSummary() {
            var extractor = new SummaryExtractor(chatClient, objectMapper);
            var node = new Node("c1", "长文本内容", Map.of());

            llmReturns("{\"text\": \"这段内容讲多屏协同。\"}");
            assertThat(extractor.extract(node)).isEqualTo("这段内容讲多屏协同。");

            llmReturns("   ");
            assertThat(extractor.extract(node)).isEmpty();

            llmReturns("抱歉无法总结");
            assertThat(extractor.extract(node)).isEmpty();
        }
    }

    @Nested
    @DisplayName("NodePotentialFilter")
    class NodePotentialFilterTest {

        private Node node(String summary) {
            var n = new Node("c1", "chunk 内容", Map.of());
            n.setSummary(summary);
            return n;
        }

        @Test
        @DisplayName("score ≤ minScore 剔除；更高分保留；无摘要跳过过滤（保留）")
        void filtersByScore() {
            var filter = new NodePotentialFilter(chatClient, objectMapper, 2);

            llmReturns("{\"score\": 2}");
            assertThat(filter.shouldRemove(node("摘要"))).isTrue();

            llmReturns("{\"score\": 3}");
            assertThat(filter.shouldRemove(node("摘要"))).isFalse();

            // ragas 原版行为：无 summary 跳过过滤（保留节点，不触发 LLM）
            assertThat(filter.shouldRemove(node(""))).isFalse();
        }

        @Test
        @DisplayName("评分失败/脏返回保留节点")
        void keepsNodeOnFailure() {
            var filter = new NodePotentialFilter(chatClient, objectMapper, 2);
            llmReturns("无法评分");
            assertThat(filter.shouldRemove(node("摘要"))).isFalse();
        }
    }

    @Nested
    @DisplayName("Jaro-Winkler")
    class JaroWinklerTest {

        @Test
        @DisplayName("相同串为 1，完全不同为 0，前缀加成生效（数值经 rapidfuzz 对照）")
        void basicSimilarity() {
            assertThat(TransformsTest.jw("华为账号", "华为账号")).isEqualTo(1.0);
            assertThat(EntityOverlapBuilder.jaroWinkler("abcd", "abcd")).isEqualTo(1.0);
            assertThat(EntityOverlapBuilder.jaroWinkler("", "")).isEqualTo(1.0);
            assertThat(EntityOverlapBuilder.jaroWinkler("aaaa", "zzzz")).isEqualTo(0.0);
            // rapidfuzz 基准：0.933333
            assertThat(EntityOverlapBuilder.jaroWinkler("多屏协同", "多屏协同模式"))
                    .isCloseTo(0.933333, org.assertj.core.data.Offset.offset(0.000001));
            // rapidfuzz 基准：0.866667（低于 0.9 匹配阈值）
            assertThat(EntityOverlapBuilder.jaroWinkler("华为账号", "华为帐号"))
                    .isCloseTo(0.866667, org.assertj.core.data.Offset.offset(0.000001));
        }
    }

    @Nested
    @DisplayName("EntityOverlapBuilder 实体重叠边")
    class EntityOverlapBuilderTest {

        private Node node(String id, Set<String> entities) {
            var n = new Node(id, "content-" + id, Map.of());
            n.setEntities(entities);
            return n;
        }

        /** 6 节点图：超高频实体（出现 6 次）被噪声剔除，其余实体最多出现 2 次。 */
        private List<Node> fixture(Set<String> extraA, Set<String> extraB) {
            var nodes = new java.util.ArrayList<Node>();
            var a = new java.util.HashSet<>(Set.of("超高频"));
            a.addAll(extraA);
            var b = new java.util.HashSet<>(Set.of("超高频"));
            b.addAll(extraB);
            nodes.add(node("a", a));
            nodes.add(node("b", b));
            for (int i = 0; i < 4; i++) {
                nodes.add(node("n" + i, Set.of("超高频", "独有" + i)));
            }
            return nodes;
        }

        @Test
        @DisplayName("共享实体达到阈值建立双向关系并携带重叠对；高频噪声实体被剔除")
        void buildsOverlapRelationship() {
            var nodes = fixture(Set.of("多屏协同"), Set.of("多屏协同", "其他"));

            var rels = new EntityOverlapBuilder().build(nodes);

            // a-b: 多屏协同×{多屏协同,其他} → 1/2 = 0.5 过阈
            assertThat(rels).hasSize(1);
            var rel = rels.getFirst();
            assertThat(rel.source() + "->" + rel.target()).isEqualTo("a->b");
            assertThat(rel.type()).isEqualTo(RelationshipType.ENTITY_OVERLAP);
            assertThat(rel.bidirectional()).isTrue();
            assertThat(rel.weight()).isEqualTo(0.5);
            @SuppressWarnings("unchecked")
            var overlapped = (List<String>) rel.properties().get("overlappedItems");
            assertThat(overlapped).containsExactly("多屏协同~多屏协同");
            // "超高频" 若未被剔除，a-b 的其他比较与所有含 独有i 的对都不会建边——隐式验证剔除生效
        }

        @Test
        @DisplayName("无共享实体不建边（噪声剔除后剩余实体互不重叠）")
        void skipsDisjointEntities() {
            var nodes = fixture(Set.of("甲主题"), Set.of("乙主题"));

            assertThat(new EntityOverlapBuilder().build(nodes)).isEmpty();
        }

        @Test
        @DisplayName("模糊匹配：多屏协同 vs 多屏协同模式（0.933 ≥ 0.9）命中")
        void fuzzyMatchAcrossVariants() {
            var nodes = fixture(Set.of("多屏协同"), Set.of("多屏协同模式"));

            var rels = new EntityOverlapBuilder().build(nodes);

            assertThat(rels).hasSize(1);
        }
    }

    @Nested
    @DisplayName("VectorCosineBuilder 摘要向量相似边")
    class VectorCosineBuilderTest {

        private Node node(String id, double[] summaryEmbedding) {
            var n = new Node(id, "c-" + id, Map.of());
            if (summaryEmbedding != null) {
                n.setSummaryEmbedding(summaryEmbedding);
            }
            return n;
        }

        @Test
        @DisplayName("过阈建边、不过阈不建、无摘要向量跳过、维度不一致降级 0")
        void buildsSimilarityRelationship() {
            var a = node("a", new double[]{1.0, 0.0});
            var b = node("b", new double[]{0.9, 0.1});
            var c = node("c", new double[]{0.0, 1.0});
            var d = node("d", null);

            var rels = new VectorCosineBuilder(0.7).build(List.of(a, b, c, d));

            assertThat(rels).hasSize(1);
            var rel = rels.getFirst();
            assertThat(rel.type()).isEqualTo(RelationshipType.SIMILARITY);
            // ragas CosineSimilarityBuilder 的边是双向的（间接簇路径枚举依赖反向通行）
            assertThat(rel.bidirectional()).isTrue();
            assertThat(rel.weight()).isCloseTo(0.994, org.assertj.core.data.Offset.offset(0.01));

            // 维度不一致降级为 0（warn + 跳过），单对坏向量不废掉整批
            assertThat(new VectorCosineBuilder(0.7).cosine(
                    new double[]{1.0}, new double[]{1.0, 2.0})).isEqualTo(0.0);
        }

        @Test
        @DisplayName("零向量余弦安全返回 0")
        void zeroVectorSafe() {
            assertThat(new VectorCosineBuilder(0.7).cosine(
                    new double[]{0.0, 0.0}, new double[]{1.0, 1.0})).isEqualTo(0.0);
        }
    }

    static double jw(String a, String b) {
        return EntityOverlapBuilder.jaroWinkler(a, b);
    }
}
