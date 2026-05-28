package com.smart.rag.chat.service;

import com.smart.rag.rag.agent.guardrail.TokenCountingChatModel;
import com.smart.rag.rag.agent.intent.IntentResult;
import com.smart.rag.rag.agent.workspace.ToolWorkspace;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;

/**
 * Strategy buildAdvisorChain() 的统一返回类型 + 执行指示。
 * <p>
 * 替代现有 AgentChainResult -- 迁移完成后删除 AgentChainResult。
 * <p>
 * 标准模式：仅 chain 非空，其余字段为 null/false。
 * Agent 模式：chain + agent 元数据 + skipXxx 执行指示。
 *
 * @param chain                 组装好的 Advisor 链
 * @param intentResult          意图分类结果（Agent 模式，nullable）
 * @param workspace             请求级 ToolWorkspace（Agent 模式，nullable）
 * @param tokenCountingModel    Token 计数装饰器（Agent 模式，nullable）
 * @param skipGlobalTools       Agent: true -- 有自建 ToolCallAdvisor
 * @param skipDbSystemPrompt    Agent: true -- 有 AgentSystemPromptAdvisor
 * @param skipDbModelOptions    Agent: true -- 使用自有模型配置
 */
public record ModeChainResult(
    List<Advisor> chain,

    // Agent 元数据（nullable）
    @Nullable IntentResult intentResult,
    @Nullable ToolWorkspace workspace,
    @Nullable TokenCountingChatModel tokenCountingModel,

    // 执行指示 -- 控制 ChatRequestSpecFactory.createSpec() 行为
    boolean skipGlobalTools,
    boolean skipDbSystemPrompt,
    boolean skipDbModelOptions
) {
    /** 标准模式的便捷工厂 -- 不跳过任何 createSpec 步骤 */
    public static ModeChainResult standard(List<Advisor> chain) {
        return new ModeChainResult(chain, null, null, null, false, false, false);
    }

    /** Agent 模式的完整工厂 -- 跳过全局 tools / DB system prompt / DB model options */
    public static ModeChainResult agent(List<Advisor> chain,
                                         IntentResult intentResult,
                                         ToolWorkspace workspace,
                                         TokenCountingChatModel tokenCountingModel) {
        return new ModeChainResult(chain, intentResult, workspace, tokenCountingModel,
            true, true, true);
    }
}
