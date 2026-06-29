package com.smart.rag.mcp;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * MCP 包依赖纪律（ArchUnit，§4.3 / implement Step 6）——把"starter 类型不跨出边界"从口号变 CI 约束。
 * <p>
 * 仅分析生产类（{@link ImportOption.DoNotIncludeTests}）：测试用 mock 的 starter 类型不受约束。
 * <p>
 * 规则（design 现实校准 D-4/D-8 后）：
 * <ul>
 *   <li>6.1 {@code core} 零 starter/agent/chat/实现包依赖（纯领域）</li>
 *   <li>6.2 仅 {@code adapter}+{@code runtime} 可 import {@code org.springframework.ai.tool..}
 *       （放宽自"adapter 唯一"——§9.1 发现面 runtime 需读 {@code ToolCallback}，见 design D-8）</li>
 *   <li>6.3 仅 {@code runtime}+{@code config} 可 import {@code org.springframework.ai.mcp..}/{@code io.modelcontextprotocol..}</li>
 *   <li>6.4 消费者（agent/chat）只依赖 {@code mcp.core} + {@code mcp.adapter}（出口① 类型转换接缝），不碰 runtime/config/health/policy 实现包</li>
 *   <li>D-4 {@code mcp.*} 不依赖 {@code infrastructure.llm..}（复用通用 {@code infrastructure.fallback}）</li>
 *   <li>{@code policy} 是下层：不依赖 runtime/adapter/config/health</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.smart.rag", importOptions = ImportOption.DoNotIncludeTests.class)
class McpDependencyRulesTest {

    @ArchTest
    static final ArchRule core_isPureDomain = noClasses()
            .that().resideInAPackage("..mcp.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "io.modelcontextprotocol..",
                    "com.smart.rag.agent..",
                    "com.smart.rag.chat..",
                    "..mcp.runtime..", "..mcp.adapter..", "..mcp.config..", "..mcp.health..", "..mcp.policy..")
            .because("mcp/core 是纯领域（接口+模型），零 Spring/starter/agent/chat/实现包依赖（§4.3 6.1）");

    @ArchTest
    static final ArchRule tool_imports_confined_to_adapter_and_runtime = noClasses()
            .that().resideInAnyPackage("..mcp.core..", "..mcp.policy..", "..mcp.health..", "..mcp.config..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.ai.tool..")
            .because("仅 mcp/adapter（出口① 类型转换）+ mcp/runtime（§9.1 发现面读 ToolCallback）可 import tool..（6.2，D-8 放宽）");

    @ArchTest
    static final ArchRule starterMcpTypes_confined_to_runtime_and_config = noClasses()
            .that().resideInAnyPackage("..mcp.core..", "..mcp.policy..", "..mcp.health..", "..mcp.adapter..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.ai.mcp..", "io.modelcontextprotocol..")
            .because("starter MCP 类型只活在 mcp/runtime + mcp/config，不跨出到 core/policy/health/adapter（§4.3 6.3）");

    @ArchTest
    static final ArchRule policy_isLowerLayer = noClasses()
            .that().resideInAPackage("..mcp.policy..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..mcp.runtime..", "..mcp.adapter..", "..mcp.config..", "..mcp.health..")
            .because("mcp/policy 是下层规则数据，不依赖实现/装配包（§4.3）");

    @ArchTest
    static final ArchRule mcp_doesNotDependOn_llmPackage = noClasses()
            .that().resideInAPackage("..com.smart.rag.mcp..")
            .should().dependOnClassesThat().resideInAPackage("com.smart.rag.infrastructure.llm..")
            .because("MCP 复用通用 infrastructure.fallback 弹性件，不依赖 LLM 专属包（design D-4）");

    @ArchTest
    static final ArchRule consumers_only_dependOn_core = noClasses()
            .that().resideInAnyPackage("..com.smart.rag.agent..", "..com.smart.rag.chat..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..mcp.runtime..", "..mcp.config..", "..mcp.health..", "..mcp.policy..")
            .because("消费者（agent/chat/业务）只依赖 mcp/core + mcp/adapter（出口① 接缝：AgentToolCallbackFactory 调 "
                    + "McpToolCallbackAdapter 产 ToolCallback[]）；mcp.adapter 公共面 = core 类型 + ToolCallback，"
                    + "不泄露 starter 类型（6.3 独立守住），等同 D-8 为 runtime 放宽 tool.. 导入。"
                    + "禁止直注 runtime/config/health/policy 实现类（§4.3 6.4，Phase 2 出口① 接线放宽）");
}
