package com.smart.rag.chat.service;

import com.smart.rag.rag.agent.guardrail.TokenCountingChatModel;
import com.smart.rag.rag.agent.intent.IntentResult;
import com.smart.rag.rag.agent.workspace.ToolWorkspace;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;

/**
 * Agent 编排链构建结果
 *
 * @param chain               组装好的 Advisor 链
 * @param intentResult        意图分类结果
 * @param workspace           请求级 ToolWorkspace
 * @param tokenCountingModel  Token 计数装饰器（可 null，非 Agent 模式时为 null）
 */
public record AgentChainResult(
    List<Advisor> chain,
    IntentResult intentResult,
    ToolWorkspace workspace,
    @Nullable TokenCountingChatModel tokenCountingModel
) {}
