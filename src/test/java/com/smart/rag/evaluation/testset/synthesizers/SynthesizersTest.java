package com.smart.rag.evaluation.testset.synthesizers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.testset.graph.KnowledgeGraph;
import com.smart.rag.evaluation.testset.graph.Node;
import com.smart.rag.evaluation.testset.graph.Relationship;
import com.smart.rag.evaluation.testset.graph.RelationshipType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 合成器单元测试：ChatClient 流式链按项目范式以 Mockito 罐头 JSON 桩化。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("查询合成器")
class SynthesizersTest {

    @Mock
    private ChatClient cheapClient;

    @Mock
    private ChatClient.ChatClientRequestSpec cheapSpec;

    @Mock
    private ChatClient.CallResponseSpec cheapResponse;

    @Mock
    private ChatClient mainClient;

    @Mock
    private ChatClient.ChatClientRequestSpec mainSpec;

    @Mock
    private ChatClient.CallResponseSpec mainResponse;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void stubFluentChain() {
        // 共享链桩：部分用例（如 isAvailable 检查）不触发 LLM 调用，lenient 避免 UnnecessaryStubbing
        lenient().when(cheapClient.prompt()).thenReturn(cheapSpec);
        lenient().when(cheapSpec.user(anyString())).thenReturn(cheapSpec);
        lenient().when(cheapSpec.call()).thenReturn(cheapResponse);
        lenient().when(mainClient.prompt()).thenReturn(mainSpec);
        lenient().when(mainSpec.user(anyString())).thenReturn(mainSpec);
        lenient().when(mainSpec.call()).thenReturn(mainResponse);
    }

    private void cheapReturns(String json) {
        when(cheapResponse.content()).thenReturn(json);
    }

    private void mainReturns(String json) {
        when(mainResponse.content()).thenReturn(json);
    }

    private static Node chunkNode(String id, List<String> themes) {
        var node = new Node(id, "内容-" + id, Map.of(), new double[]{1.0});
        node.setThemes(themes);
        return node;
    }

    private static Node entityNode(String id, String... entities) {
        var node = new Node(id, "内容-" + id, Map.of(), new double[]{1.0});
        node.setEntities(new java.util.LinkedHashSet<>(List.of(entities)));
        return node;
    }

    private static final List<Persona> PERSONAS = List.of(
            new Persona("一线业务人员", "日常使用知识库解决具体业务问题"),
            new Persona("技术工程师", "关注系统设计与技术细节"));

    @Nested
    @DisplayName("SingleHopSpecificSynthesizer")
    class SingleHop {

        @Test
        @DisplayName("场景生成 + 样本生成全链路（罐头 JSON）")
        void generatesScenarioAndSample() {
            var kg = new KnowledgeGraph();
            kg.addNode(entityNode("chunk-1", "多屏协同", "华为账号"));
            var synthesizer = new SingleHopSpecificSynthesizer(
                    cheapClient, mainClient, objectMapper, 42);

            assertThat(synthesizer.isAvailable(kg)).isTrue();
            cheapReturns("{\"mapping\": {\"一线业务人员\": [\"多屏协同\", \"华为账号\"]}}");
            var scenarios = synthesizer.generateScenarios(2, kg, PERSONAS);
            assertThat(scenarios).isNotEmpty();
            assertThat(scenarios.getFirst()).isInstanceOf(SingleHopScenario.class);

            mainReturns("{\"query\": \"怎么开启多屏协同？\", \"answer\": \"下拉控制中心点击超级终端。\"}");
            var sample = synthesizer.generateSample(scenarios.getFirst());
            assertThat(sample.userInput()).isEqualTo("怎么开启多屏协同？");
            assertThat(sample.reference()).isEqualTo("下拉控制中心点击超级终端。");
            assertThat(sample.referenceContexts()).hasSize(1);
            assertThat(sample.synthesizerName()).isEqualTo("single_hop_specific_query_synthesizer");
        }

        @Test
        @DisplayName("无实体节点时 isAvailable=false（编排器据此降级）")
        void unavailableWithoutEntities() {
            var kg = new KnowledgeGraph();
            kg.addNode(chunkNode("chunk-1", List.of("主题")));
            var synthesizer = new SingleHopSpecificSynthesizer(
                    cheapClient, mainClient, objectMapper, 42);
            assertThat(synthesizer.isAvailable(kg)).isFalse();
        }

        @Test
        @DisplayName("main 模型返回非 JSON 时样本生成抛异常（由编排层兜住）")
        void failsOnGarbageResponse() {
            var kg = new KnowledgeGraph();
            kg.addNode(entityNode("chunk-1", "多屏协同"));
            var synthesizer = new SingleHopSpecificSynthesizer(
                    cheapClient, mainClient, objectMapper, 42);
            cheapReturns("{\"mapping\": {\"一线业务人员\": [\"多屏协同\"]}}");
            var scenarios = synthesizer.generateScenarios(1, kg, PERSONAS);
            mainReturns("抱歉我不能回答");
            assertThatThrownBy(() -> synthesizer.generateSample(scenarios.getFirst()))
                    .isInstanceOf(Exception.class);
        }
    }

    @Nested
    @DisplayName("MultiHopSpecificSynthesizer")
    class MultiHopSpecific {

        @Test
        @DisplayName("实体重叠三元组驱动的多跳场景与 <1-hop>/<2-hop> 标签")
        void generatesFromOverlapTriplets() {
            var kg = new KnowledgeGraph();
            kg.addNode(entityNode("chunk-1", "多屏协同"));
            kg.addNode(entityNode("chunk-2", "多屏协同", "畅连通话"));
            kg.addRelationship(new Relationship("chunk-1", "chunk-2",
                    RelationshipType.ENTITY_OVERLAP, 0.5, true,
                    Map.of("overlappedItems", List.of("多屏协同~多屏协同"))));
            var synthesizer = new MultiHopSpecificSynthesizer(
                    cheapClient, mainClient, objectMapper, 42);

            assertThat(synthesizer.isAvailable(kg)).isTrue();
            cheapReturns("{\"mapping\": {\"一线业务人员\": [\"多屏协同\", \"畅连通话\"]}}");
            var scenarios = synthesizer.generateScenarios(2, kg, PERSONAS);
            assertThat(scenarios).isNotEmpty();

            mainReturns("{\"query\": \"多屏协同和畅连什么关系？\", \"answer\": \"两者都依赖华为账号。\"}");
            var sample = synthesizer.generateSample(scenarios.getFirst());
            assertThat(sample.referenceContexts()).hasSize(2);
            assertThat(sample.referenceContexts().getFirst()).startsWith("<1-hop>");
            assertThat(sample.referenceContexts().get(1)).startsWith("<2-hop>");
        }

        @Test
        @DisplayName("无实体重叠边时 isAvailable=false")
        void unavailableWithoutOverlap() {
            var kg = new KnowledgeGraph();
            kg.addNode(entityNode("chunk-1", "甲"));
            kg.addNode(entityNode("chunk-2", "乙"));
            var synthesizer = new MultiHopSpecificSynthesizer(
                    cheapClient, mainClient, objectMapper, 42);
            assertThat(synthesizer.isAvailable(kg)).isFalse();
        }
    }

    @Nested
    @DisplayName("MultiHopAbstractSynthesizer")
    class MultiHopAbstract {

        @Test
        @DisplayName("相似边间接簇 + 概念组合 LLM 桩化全链路")
        void generatesFromSimilarityClusters() {
            var kg = new KnowledgeGraph();
            kg.addNode(chunkNode("chunk-1", List.of("流量管理", "移动网络")));
            kg.addNode(chunkNode("chunk-2", List.of("流量管理", "省流量模式")));
            kg.addRelationship(Relationship.of("chunk-1", "chunk-2",
                    RelationshipType.SIMILARITY, 0.9));
            var synthesizer = new MultiHopAbstractSynthesizer(
                    cheapClient, mainClient, objectMapper, 42);

            assertThat(synthesizer.isAvailable(kg)).isTrue();
            // 两次 cheap 调用：概念组合 → persona 匹配
            when(cheapResponse.content())
                    .thenReturn("{\"combinations\": [[\"流量管理\", \"省流量模式\"]]}")
                    .thenReturn("{\"mapping\": {\"一线业务人员\": [\"流量管理\", \"省流量模式\"]}}");
            var scenarios = synthesizer.generateScenarios(2, kg, PERSONAS);
            assertThat(scenarios).isNotEmpty();

            mainReturns("{\"query\": \"流量管理和省流量模式什么区别？\", \"answer\": \"前者管理用量，后者限制后台。\"}");
            var sample = synthesizer.generateSample(scenarios.getFirst());
            assertThat(sample.referenceContexts()).hasSize(2);
            assertThat(sample.userInput()).contains("流量管理");
        }

        @Test
        @DisplayName("无相似边时 isAvailable=false")
        void unavailableWithoutSimilarity() {
            var kg = new KnowledgeGraph();
            kg.addNode(chunkNode("chunk-1", List.of("主题")));
            var synthesizer = new MultiHopAbstractSynthesizer(
                    cheapClient, mainClient, objectMapper, 42);
            assertThat(synthesizer.isAvailable(kg)).isFalse();
        }
    }
}
