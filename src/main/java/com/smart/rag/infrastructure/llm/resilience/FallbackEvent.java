package com.smart.rag.infrastructure.llm.resilience;

import com.smart.rag.infrastructure.llm.LlmCapability;

/**
 * 降级事件 — 记录一次模型切换
 * <p>
 * 发布时机：阻塞式 {@code execute()} 和流式 {@code executeStream()} 在切换到下一个模型时发布。
 * 消费方：
 * <ul>
 *   <li>UI 层：提示用户"因模型切换，前文可能不完整"</li>
 *   <li>Metrics：采集 {@code llm.fallback.invocations} 计数器（标签：capability, from, to）</li>
 *   <li>日志：记录降级链路追踪</li>
 * </ul>
 */
public record FallbackEvent(
    /** 发生降级的能力类型 */
    LlmCapability capability,
    /** 失败的模型 candidateId */
    String fromCandidateId,
    /** 降级目标模型 candidateId */
    String toCandidateId,
    /** 触发降级的异常（可能是 RemoteException 子类或任何 Throwable） */
    Throwable cause
) {}
