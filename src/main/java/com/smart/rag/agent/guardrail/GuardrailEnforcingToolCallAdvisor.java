package com.smart.rag.agent.guardrail;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * 护栏强制的 ToolCallAdvisor — 每轮 ReAct 前检查 {@link AgentGuardrails}（design §4.3，P4b）。
 * <p>
 * 子类化 {@link ToolCallAdvisor}，override {@code doBeforeStream}/{@code doBeforeCall}
 * （Poc10 证每轮触发），调 {@link AgentGuardrails#check}：
 * <ul>
 *   <li>STOP（迭代/token 超限）→ 抛 {@link GuardrailHardStopException} 中断 ReAct</li>
 *   <li>WARN/OK → 继续（WARN 软干预注入属另一机制，P4b 先只处理硬中断）</li>
 * </ul>
 * <p>
 * 同时修复阻塞态 no-op：此前 {@code check()} 无人调用，{@code totalIterations} 恒 1；
 * 现 doBeforeCall 每轮调用，阻塞/流式硬上界均生效。
 * <p>
 * {@code check(null)}：doBefore 发生在模型响应前，未知当前轮工具名，指标 3（连续工具）在此处跳过
 * （consecutiveSameTool 重置为 1）；硬中断指标 1/2（迭代/token）不依赖工具名，正常生效。
 */
public class GuardrailEnforcingToolCallAdvisor extends ToolCallAdvisor {

    private final AgentGuardrails guardrails;

    public GuardrailEnforcingToolCallAdvisor(ToolCallingManager toolCallingManager, int order,
                                             AgentGuardrails guardrails) {
        super(toolCallingManager, order);
        this.guardrails = guardrails;
    }

    @Override
    protected ChatClientRequest doBeforeStream(ChatClientRequest request, StreamAdvisorChain chain) {
        enforceGuardrails();
        return super.doBeforeStream(request, chain);
    }

    @Override
    protected ChatClientRequest doBeforeCall(ChatClientRequest request, CallAdvisorChain chain) {
        enforceGuardrails();
        return super.doBeforeCall(request, chain);
    }

    private void enforceGuardrails() {
        AgentGuardrails.GuardrailCheck check = guardrails.check(null);
        if (check.shouldStop()) {
            throw new GuardrailHardStopException(check.reason(), check.message());
        }
    }
}
