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
        @DisplayName("所有 prompt 已清理 XML 声明和 prompt 根元素外壳")
        void allPrompts_wrapperStripped() {
            for (AgentIntent intent : AgentIntent.values()) {
                String prompt = loader.getPrompt(intent);
                assertThat(prompt).as("intent %s prompt non-null", intent).isNotNull();
                // 已剥离的元信息外壳
                assertThat(prompt).as("intent %s no xml declaration", intent)
                    .doesNotContain("<?xml");
                assertThat(prompt).as("intent %s no <prompt tag", intent)
                    .doesNotContain("<prompt");
                assertThat(prompt).as("intent %s no </prompt> tag", intent)
                    .doesNotContain("</prompt>");
                // 内部结构标签必须保留
                assertThat(prompt).as("intent %s retains <role>", intent)
                    .contains("<role>");
            }
        }

        @Test
        @DisplayName("清理后不含 XML 声明和 prompt 根元素，但保留内部结构标签")
        void stripped_correctly() {
            String prompt = loader.getPrompt(AgentIntent.DEEP_RETRIEVAL);
            assertThat(prompt).doesNotStartWith("<?xml");
            assertThat(prompt).doesNotContain("<prompt");
            assertThat(prompt).doesNotContain("</prompt>");
            // 内部结构标签完整保留（Prompt Engineering 语义锚点）
            assertThat(prompt).contains("<role>");
            assertThat(prompt).contains("<workflow>");
            assertThat(prompt).contains("<decision_rules>");
            assertThat(prompt).contains("<examples>");
            assertThat(prompt).contains("<output_format>");
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

    @Nested
    @DisplayName("stripWrapper 清理逻辑（纯函数边界 case）")
    class StripWrapperLogic {

        @Test
        @DisplayName("剥离标准 XML 声明 + prompt 根，保留内部内容")
        void standardStrip() {
            String raw = """
                <?xml version="1.0" encoding="UTF-8"?>
                <prompt version="1.0" model="DEEP_RETRIEVAL">

                    <role>测试角色</role>
                    <workflow>测试流程</workflow>

                </prompt>
                """;
            String stripped = AgentPromptLoader.stripWrapper(raw);

            assertThat(stripped).doesNotContain("<?xml");
            assertThat(stripped).doesNotContain("<prompt");
            assertThat(stripped).doesNotContain("</prompt>");
            assertThat(stripped).contains("<role>测试角色</role>");
            assertThat(stripped).contains("<workflow>测试流程</workflow>");
            // 首尾已 trim
            assertThat(stripped).startsWith("<role>");
            assertThat(stripped).endsWith("</workflow>");
        }

        @Test
        @DisplayName("prompt 标签带各种属性都能剥离")
        void promptWithVariousAttributes() {
            String raw = "<?xml version=\"1.0\"?>\n<prompt version=\"2.0\" model=\"X\" custom=\"y\">\n<role>r</role>\n</prompt>";
            String stripped = AgentPromptLoader.stripWrapper(raw);

            assertThat(stripped).isEqualTo("<role>r</role>");
        }

        @Test
        @DisplayName("内部含 prompt 字样的标签不会被误剥（只剥根元素）")
        void innerPromptTagPreserved() {
            // 假设内部有 <example><prompt>...</prompt></example> 这种结构（虽不常见）
            String raw = "<?xml version=\"1.0\"?>\n<prompt model=\"X\">\n<role>r</role>\n<example><prompt>nested</prompt></example>\n</prompt>";
            String stripped = AgentPromptLoader.stripWrapper(raw);

            // 根元素已剥，但内部的 nested <prompt> 保留（replaceFirst 只剥首个 <prompt...>）
            assertThat(stripped).contains("<prompt>nested</prompt>");
            assertThat(stripped).contains("<role>r</role>");
        }

        @Test
        @DisplayName("XML 声明含不同属性也能剥离")
        void variousXmlDeclarations() {
            String raw = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<prompt model=\"X\">\n<role>r</role>\n</prompt>";
            String stripped = AgentPromptLoader.stripWrapper(raw);

            assertThat(stripped).doesNotContain("<?xml");
            assertThat(stripped).isEqualTo("<role>r</role>");
        }
    }
}
