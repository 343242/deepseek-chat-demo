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
 * Agent System Prompt Advisor（v5 静态/动态拆分，前缀缓存优化）。
 * <p>
 * before() 把 prompt 拆成两个 SystemMessage：
 * <ol>
 *   <li>静态（首位）= default.xml 基座 + 意图模板，跨 ReAct 轮次字节稳定 → 前缀缓存命中。</li>
 *   <li>动态（末尾）= CAG 段 + 中间答案 + 护栏，每轮变化 → miss（紧邻生成点，仅自身重算）。</li>
 * </ol>
 * 消息序：{@code [system:静态] → [tools] → [tool历史/user] → [system:动态]}。
 * 动态必须在 tools 与历史之后（v5 修正）——否则 tool 定义+历史每轮全重算，拆分失效。
 * <p>
 * order=1，在 ToolCallAdvisor(order=2) 之前执行。每轮 before() 从原 messages 过滤旧 SystemMessage
 * 后重建，避免 ReAct 累积重复。
 */
public class AgentSystemPromptAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AgentSystemPromptAdvisor.class);

    /** 中间答案注入的最大字符数预算 */
    private static final int INTERMEDIATE_ANSWERS_BUDGET = 20_000;

    private final AgentIntent intent;
    private final String staticSystemPrompt;       // default.xml 基座 + 意图模板（跨轮次稳定）
    private final @Nullable String cagSegment;     // CAG 上下文段（每请求可能不同）
    private final ToolWorkspace workspace;          // 与 Tool 闭包共享同一个引用
    private final @Nullable AgentGuardrails guardrails;

    public AgentSystemPromptAdvisor(AgentIntent intent, String staticSystemPrompt,
                                    @Nullable String cagSegment,
                                    ToolWorkspace workspace, @Nullable AgentGuardrails guardrails) {
        this.intent = intent;
        this.staticSystemPrompt = staticSystemPrompt;
        this.cagSegment = cagSegment;
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
        String guardrailMessage = checkGuardrails();
        String intermediateSummary = workspace.getIntermediateAnswersSummaryBounded(INTERMEDIATE_ANSWERS_BUDGET);

        // 动态尾：CAG 段 + 中间答案 + 护栏
        StringBuilder dynamic = new StringBuilder();
        if (cagSegment != null && !cagSegment.isBlank()) {
            dynamic.append(cagSegment).append("\n\n");
        }
        if (intermediateSummary != null && !intermediateSummary.isBlank()) {
            dynamic.append("## 已收集的信息\n").append(intermediateSummary).append("\n\n");
        }
        if (guardrailMessage != null) {
            dynamic.append("## 系统提醒\n").append(guardrailMessage);
        }
        String dynamicPart = dynamic.length() == 0 ? null : dynamic.toString().trim();

        // 重建 messages：静态 SystemMessage 首位 + 非system历史 + 动态 SystemMessage 末尾
        List<Message> originalMessages = request.prompt().getInstructions();
        List<Message> newMessages = new ArrayList<>(originalMessages.size() + 2);
        newMessages.add(new SystemMessage(staticSystemPrompt));   // ① 首位：静态
        for (Message m : originalMessages) {                       // ② history + user + tool（过滤旧 SystemMessage 防累积）
            if (!(m instanceof SystemMessage)) {
                newMessages.add(m);
            }
        }
        if (dynamicPart != null && !dynamicPart.isBlank()) {       // ③ 末尾：动态
            newMessages.add(new SystemMessage(dynamicPart));
        }

        log.debug("Agent system prompt: intent={}, staticLen={}, dynamicLen={}, intermediateAnswers={}",
            intent, staticSystemPrompt.length(),
            dynamicPart != null ? dynamicPart.length() : 0, workspace.getIntermediateAnswers().size());

        return request.mutate()
            .prompt(new Prompt(newMessages, request.prompt().getOptions()))
            .build();
    }

    @Override
    @NonNull
    public ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain chain) {
        return response;
    }

    /**
     * 执行护栏检查，返回需要注入的消息（null 表示通过）
     */
    private @Nullable String checkGuardrails() {
        if (guardrails == null) {
            return null;
        }
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
