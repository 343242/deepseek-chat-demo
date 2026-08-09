package com.smart.rag.agent.guardrail;

/**
 * Agent 护栏硬中断异常 — ReAct 循环迭代/token 超限时，由 {@link GuardrailEnforcingToolCallAdvisor}
 * 在 {@code doBeforeStream}/{@code doBeforeCall} 每轮检查抛出（design §4.3，P4b）。
 * <p>
 * <ul>
 *   <li>流式：抛入 Flux → ON_ERROR → 不落库（design chat-stream-cancel.md §5.2，取消即作废）</li>
 *   <li>阻塞：{@code execute()} catch → {@code degradationStrategy.shouldDegrade} 判定降级或直接抛</li>
 * </ul>
 */
public class GuardrailHardStopException extends RuntimeException {

    private final String reason;

    public GuardrailHardStopException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** 触发原因标识（ITERATION_LIMIT / TOKEN_LIMIT） */
    public String getReason() {
        return reason;
    }
}
