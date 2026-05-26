package com.smart.rag.rag.agent.advisor;

import com.smart.rag.rag.agent.intent.AgentIntent;
import com.smart.rag.rag.agent.workspace.ToolWorkspace;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent System Prompt Advisor
 * <p>
 * 1. 根据意图注入动态 System Prompt（含原子决策引导、自省格式、检索代价规则、CAG 上下文）
 * 2. 每轮 ReAct 循环前从 Workspace 读取中间答案注入
 * 3. order=1，在 ToolCallAdvisor(order=2) 之前执行
 * <p>
 * 构造时接收 ToolWorkspace 引用（与 Tool 闭包共享同一个对象引用），
 * before() 每轮从 workspace 读取中间答案，追加到 System Prompt 末尾。
 * 每次请求创建新实例（非单例 Bean），因为 intent/mergedSystemPrompt/workspace 都是请求级别的。
 */
public class AgentSystemPromptAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AgentSystemPromptAdvisor.class);

    private final AgentIntent intent;
    private final String mergedSystemPrompt;  // Agent Prompt + CAG Context 已合并
    private final ToolWorkspace workspace;     // 与 Tool 闭包共享同一个引用

    public AgentSystemPromptAdvisor(AgentIntent intent, String mergedSystemPrompt, ToolWorkspace workspace) {
        this.intent = intent;
        this.mergedSystemPrompt = mergedSystemPrompt;
        this.workspace = workspace;
    }

    @Override
    @NonNull
    public String getName() {
        return "AgentSystemPromptAdvisor";
    }

    @Override
    public int getOrder() {
        return 1; // 在 ToolCallAdvisor(order=2) 之前执行
    }

    @Override
    @NonNull
    public ChatClientRequest before(@NonNull ChatClientRequest request, @NonNull AdvisorChain chain) {
        // 构建最终 System Prompt = 基础 Prompt + 中间答案（如有）
        String finalPrompt = mergedSystemPrompt;
        String intermediateSummary = workspace.getIntermediateAnswersSummary();
        if (intermediateSummary != null && !intermediateSummary.isBlank()) {
            finalPrompt += "\n\n## 已收集的信息\n" + intermediateSummary;
        }

        // 构建新 messages 列表，SystemMessage 在首位
        List<Message> newMessages = new ArrayList<>();
        newMessages.add(new SystemMessage(finalPrompt));
        newMessages.addAll(request.prompt().getInstructions());

        log.debug("Agent system prompt injected: intent={}, intermediateAnswers={}, promptLength={}",
            intent, workspace.getIntermediateAnswers().size(), finalPrompt.length());

        return request.mutate()
            .prompt(new Prompt(newMessages))
            .build();
    }

    @Override
    @NonNull
    public ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain chain) {
        // 不修改响应
        return response;
    }
}
