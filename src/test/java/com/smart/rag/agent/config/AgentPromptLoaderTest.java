package com.smart.rag.agent.config;

import com.smart.rag.mode.AgentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentPromptLoader} 单元测试。
 * <p>
 * 验证 4 个意图 prompt 文件能从 classpath 正确加载，且 XML 标签结构与
 * Prompt Engineering 优化后的关键元素齐全。
 */
class AgentPromptLoaderTest {

    private AgentPromptLoader loader;

    @BeforeEach
    void setUp() {
        loader = new AgentPromptLoader();
        loader.load(); // 触发 @PostConstruct 逻辑
    }

    @Nested
    @DisplayName("4 个意图 prompt 加载")
    class FourIntentsLoaded {

        @Test
        @DisplayName("DIRECT_ANSWER 加载非空")
        void directAnswer_loaded() {
            String prompt = loader.getPrompt(AgentIntent.DIRECT_ANSWER);
            assertThat(prompt).isNotNull().isNotBlank();
            assertThat(prompt).contains("<role>");
            assertThat(prompt).contains("知识助手");
        }

        @Test
        @DisplayName("RETRIEVAL 加载非空且含自省/中间答案引导")
        void retrieval_loadedWithReflectionAndIntermediateAnswer() {
            String prompt = loader.getPrompt(AgentIntent.RETRIEVAL);
            assertThat(prompt).isNotNull().isNotBlank();
            assertThat(prompt).contains("知识库检索分析师");          // 角色定义
            assertThat(prompt).contains("<reflection_schema>");      // 自省标记 schema
            assertThat(prompt).contains("<intermediate_answer_schema>"); // 中间答案标记 schema
            assertThat(prompt).contains("<decision_rules>");         // 规则化决策树
            assertThat(prompt).contains("<example>");                // few-shot 示例
        }

        @Test
        @DisplayName("DEEP_RETRIEVAL 加载非空且含原子决策+双路径示例")
        void deepRetrieval_loadedWithAtomicDecisionAndExamples() {
            String prompt = loader.getPrompt(AgentIntent.DEEP_RETRIEVAL);
            assertThat(prompt).isNotNull().isNotBlank();
            assertThat(prompt).contains("复杂问题深度分析师");          // 角色定义
            assertThat(prompt).contains("<atomic_decision_schema>"); // DeepRAG 原子决策
            assertThat(prompt).contains("<reflection_schema>");
            assertThat(prompt).contains("<intermediate_answer_schema>");
            assertThat(prompt).contains("retrieve 路径");             // 双路径示例
            assertThat(prompt).contains("parametric 路径");
            assertThat(prompt).contains("<cost_awareness>");         // 检索代价感知
        }

        @Test
        @DisplayName("GENERAL_TOOL 加载非空")
        void generalTool_loaded() {
            String prompt = loader.getPrompt(AgentIntent.GENERAL_TOOL);
            assertThat(prompt).isNotNull().isNotBlank();
            assertThat(prompt).contains("工具助手");
            assertThat(prompt).contains("<workflow>");
        }
    }

    @Nested
    @DisplayName("Prompt Engineering 优化要点验证")
    class PromptEngineeringOptimizations {

        @Test
        @DisplayName("RETRIEVAL 决策规则用 if-then 规则块（非散文）")
        void retrieval_decisionRulesStructured() {
            String prompt = loader.getPrompt(AgentIntent.RETRIEVAL);
            assertThat(prompt).contains("<rule if=");
            assertThat(prompt).contains("then=");
        }

        @Test
        @DisplayName("RETRIEVAL 输出格式用肯定式指令（只包含...，非 不要包含）")
        void retrieval_positiveGuidance() {
            String prompt = loader.getPrompt(AgentIntent.RETRIEVAL);
            assertThat(prompt).contains("只包含");
            // 不再使用否定式"不要包含这些标记内容"
            assertThat(prompt).doesNotContain("不要包含这些标记内容");
        }

        @Test
        @DisplayName("DEEP_RETRIEVAL 检索代价感知为肯定式（避免重新检索，非 不要重新检索）")
        void deepRetrieval_costAwarenessPositive() {
            String prompt = loader.getPrompt(AgentIntent.DEEP_RETRIEVAL);
            assertThat(prompt).contains("避免重新检索");
            assertThat(prompt).contains("避免调用检索工具");
        }

        @Test
        @DisplayName("所有 prompt 都是合法 XML（含 <?xml 声明 + <prompt> 根元素）")
        void allPrompts_areValidXml() {
            for (AgentIntent intent : AgentIntent.values()) {
                String prompt = loader.getPrompt(intent);
                assertThat(prompt).as("intent %s prompt non-null", intent).isNotNull();
                assertThat(prompt).startsWith("<?xml");
                assertThat(prompt).contains("<prompt ");
                assertThat(prompt).contains("</prompt>");
            }
        }
    }

    @Test
    @DisplayName("4 个意图全部加载（无遗漏）")
    void allFourIntentsPresent() {
        for (AgentIntent intent : AgentIntent.values()) {
            assertThat(loader.getPrompt(intent))
                .as("intent %s must be loaded", intent)
                .isNotNull();
        }
    }
}
