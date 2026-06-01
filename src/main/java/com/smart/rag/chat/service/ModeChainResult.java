package com.smart.rag.chat.service;

import com.smart.rag.infrastructure.agent.guardrail.TokenCountingChatModel;
import com.smart.rag.agent.intent.IntentResult;
import com.smart.rag.infrastructure.agent.workspace.ToolWorkspace;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;

/**
 * Strategy buildAdvisorChain() 的统一返回类型。
 * <p>
 * 标准模式：仅 chain 非空，其余字段为 null。
 * Agent 模式：chain + agent 元数据。
 *
 * @param chain                 组装好的 Advisor 链
 * @param intentResult          意图分类结果（Agent 模式，nullable）
 * @param workspace             请求级 ToolWorkspace（Agent 模式，nullable）
 * @param tokenCountingModel    Token 计数装饰器（Agent 模式，nullable）
 */
public record ModeChainResult(
    List<Advisor> chain,

    @Nullable IntentResult intentResult,
    @Nullable ToolWorkspace workspace,
    @Nullable TokenCountingChatModel tokenCountingModel
) {
    public static ModeChainResult standard(List<Advisor> chain) {
        return new ModeChainResult(chain, null, null, null);
    }

    public static ModeChainResult agent(List<Advisor> chain,
                                         IntentResult intentResult,
                                         ToolWorkspace workspace,
                                         TokenCountingChatModel tokenCountingModel) {
        return new ModeChainResult(chain, intentResult, workspace, tokenCountingModel);
    }
}
