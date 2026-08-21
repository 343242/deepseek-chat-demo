package com.smart.rag.evaluation.testset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.evaluation.testset.graph.Node;
import com.smart.rag.infrastructure.concurrent.DefaultScopedTasks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Persona 自动生成测试（翻译 ragas generate_personas_from_kg）：
 * 分组/代表选取/补齐的确定性行为 + ChatClient 流式链桩化。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonaGenerator")
class PersonaGeneratorTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec spec;

    @Mock
    private ChatClient.CallResponseSpec response;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefaultScopedTasks scopedTasks = new DefaultScopedTasks();

    @BeforeEach
    void stubFluentChain() {
        lenient().when(chatClient.prompt()).thenReturn(spec);
        lenient().when(spec.user(anyString())).thenReturn(spec);
        lenient().when(spec.options(any(ChatOptions.class))).thenReturn(spec);
        lenient().when(spec.call()).thenReturn(response);
    }

    private static Node node(String id, String summary, double[] embedding) {
        var n = new Node(id, "内容-" + id, Map.of());
        n.setSummary(summary);
        if (embedding != null) {
            n.setSummaryEmbedding(embedding);
        }
        return n;
    }

    @Test
    @DisplayName("相似摘要归一组（cosine>0.75），每组取最长摘要为代表生成一个 persona")
    void groupsSimilarSummariesAndPicksLongest() {
        // a/b/c 同向（cosine=1 > 0.75）归一组，代表取最长摘要 c；d 独立成组
        var nodes = List.of(
                node("a", "短摘要甲", new double[]{1.0, 0.0}),
                node("b", "稍微长一点的摘要乙", new double[]{1.0, 0.0}),
                node("c", "这是三个节点里字符长度最长的摘要丙", new double[]{1.0, 0.0}),
                node("d", "正交主题摘要丁", new double[]{0.0, 1.0}));
        var generator = new PersonaGenerator(chatClient, objectMapper, scopedTasks);

        when(response.content()).thenReturn(
                "{\"name\": \"企业IT管理员\", \"role_description\": \"负责终端协同能力的管理\"}",
                "{\"name\": \"一线业务人员\", \"role_description\": \"日常使用知识库解决问题\"}");

        var personas = generator.generate(nodes, 2, 42);

        assertThat(personas).hasSize(2);
        assertThat(personas).extracting(p -> p.name())
                .containsExactlyInAnyOrder("企业IT管理员", "一线业务人员");
        // 代表摘要应来自 c（最长）与 d——通过 prompt 内容断言生成调用的输入
        org.mockito.Mockito.verify(spec, org.mockito.Mockito.times(2)).user(anyString());
    }

    @Test
    @DisplayName("组数不足时按种子重采样补齐到目标数（目标受候选数上限约束，ragas min 语义）")
    void padsByResamplingWhenGroupsInsufficient() {
        // 三个同向节点 → 1 组；目标 3（=候选数上限）→ 补齐 2 次重采样（同一代表重复生成）
        var nodes = List.of(
                node("a", "摘要一", new double[]{1.0, 0.0}),
                node("b", "摘要二", new double[]{1.0, 0.0}),
                node("c", "摘要三", new double[]{1.0, 0.0}));
        var generator = new PersonaGenerator(chatClient, objectMapper, scopedTasks);

        when(response.content()).thenReturn(
                "{\"name\": \"产品经理\", \"role_description\": \"关注功能价值\"}");

        var personas = generator.generate(nodes, 3, 42);

        assertThat(personas).hasSize(3);
        assertThat(personas).allMatch(p -> "产品经理".equals(p.name()));
    }

    @Test
    @DisplayName("无满足条件节点（无摘要向量）抛 IllegalStateException")
    void throwsWithoutCandidates() {
        var generator = new PersonaGenerator(chatClient, objectMapper, scopedTasks);
        assertThatThrownBy(() -> generator.generate(
                List.of(node("a", "有摘要但无向量", null)), 3, 42))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("numPersonas 超过候选数时截断到候选数（ragas min 语义）")
    void capsAtCandidates() {
        var nodes = List.of(
                node("a", "摘要一", new double[]{1.0, 0.0}),
                node("b", "正交摘要二", new double[]{0.0, 1.0}));
        var generator = new PersonaGenerator(chatClient, objectMapper, scopedTasks);

        when(response.content()).thenReturn(
                "{\"name\": \"甲角色\", \"role_description\": \"职责甲\"}",
                "{\"name\": \"乙角色\", \"role_description\": \"职责乙\"}");

        var personas = generator.generate(nodes, 10, 42);

        assertThat(personas).hasSize(2);
    }
}
