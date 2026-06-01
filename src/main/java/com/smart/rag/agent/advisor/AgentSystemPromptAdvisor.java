package com.smart.rag.agent.advisor;

import com.smart.rag.agent.guardrail.AgentGuardrails;
import com.smart.rag.agent.intent.AgentIntent;
import com.smart.rag.agent.workspace.ToolWorkspace;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 * 4. 护栏检查：每轮调用 AgentGuardrails，STOP 时注入停止指令，WARN 时注入提醒
 * <p>
 * 容量控制：中间答案注入受字符预算约束，超出时截断低优先级内容。
 * <p>
 * 构造时接收 ToolWorkspace 引用（与 Tool 闭包共享同一个对象引用），
 * before() 每轮从 workspace 读取中间答案，追加到 System Prompt 末尾。
 * 每次请求创建新实例（非单例 Bean），因为 intent/mergedSystemPrompt/workspace 都是请求级别的。
 */
public class AgentSystemPromptAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AgentSystemPromptAdvisor.class);

    /** 中间答案注入到 system prompt 的最大字符数预算 */
    private static final int INTERMEDIATE_ANSWERS_BUDGET = 20_000;

    private final AgentIntent intent;
    private final String mergedSystemPrompt;  // Agent Prompt + CAG Context 已合并
    private final ToolWorkspace workspace;     // 与 Tool 闭包共享同一个引用
    private final @Nullable AgentGuardrails guardrails; // 可空，未接线时无护栏

    public AgentSystemPromptAdvisor(AgentIntent intent, String mergedSystemPrompt,
                                    ToolWorkspace workspace, @Nullable AgentGuardrails guardrails) {
        this.intent = intent;
        this.mergedSystemPrompt = mergedSystemPrompt;
        this.workspace = workspace;
        this.guardrails = guardrails;
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
        // 护栏检查
        String guardrailMessage = checkGuardrails();

        // 构建最终 System Prompt = 基础 Prompt + 中间答案（如有，受预算约束）+ 护栏消息（如有）
        String finalPrompt = mergedSystemPrompt;
        String intermediateSummary = workspace.getIntermediateAnswersSummaryBounded(INTERMEDIATE_ANSWERS_BUDGET);
        if (intermediateSummary != null && !intermediateSummary.isBlank()) {
            finalPrompt += "\n\n## 已收集的信息\n" + intermediateSummary;
        }
        if (guardrailMessage != null) {
            finalPrompt += "\n\n## 系统提醒\n" + guardrailMessage;
        }

        // 查找已有 SystemMessage 的位置，替换而非追加（防止 ReAct 每轮重复累积）
        List<Message> originalMessages = request.prompt().getInstructions();
        int existingSystemIndex = -1;
        for (int i = 0; i < originalMessages.size(); i++) {
            if (originalMessages.get(i) instanceof SystemMessage) {
                existingSystemIndex = i;
                break;
            }
        }

        List<Message> newMessages = new ArrayList<>(originalMessages.size());
        if (existingSystemIndex >= 0) {
            // 替换已有 SystemMessage
            for (int i = 0; i < originalMessages.size(); i++) {
                if (i == existingSystemIndex) {
                    newMessages.add(new SystemMessage(finalPrompt));
                } else {
                    newMessages.add(originalMessages.get(i));
                }
            }
        } else {
            // 首次：SystemMessage 在首位
            newMessages.add(new SystemMessage(finalPrompt));
            newMessages.addAll(originalMessages);
        }

        log.debug("Agent system prompt injected: intent={}, intermediateAnswers={}, promptLength={}, systemReplaced={}",
            intent, workspace.getIntermediateAnswers().size(), finalPrompt.length(), existingSystemIndex >= 0);

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

    /**
     * 执行护栏检查，返回需要注入的消息（null 表示通过）
     */
    private @Nullable String checkGuardrails() {
        if (guardrails == null) {
            return null;
        }

        // 使用护栏中追踪的最近 Tool 名称
        String lastToolName = guardrails.getLastToolName();

        AgentGuardrails.GuardrailCheck check = guardrails.check(lastToolName);

        if (check.shouldStop()) {
            log.warn("Agent guardrail STOP triggered: reason={}, message={}", check.reason(), check.message());
            return "[系统指令 - 必须遵守] " + check.message()
                + "\n\n请立即停止调用任何工具，直接使用已收集的信息生成最终回答。不要再尝试检索。";
        }

        if (check.shouldWarn()) {
            log.info("Agent guardrail WARN: reason={}, message={}", check.reason(), check.message());
            return check.message();
        }

        return null;
    }
}
