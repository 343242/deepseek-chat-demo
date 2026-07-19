package com.smart.rag.agent.dto;

import java.util.List;

/**
 * 原子决策结果 — DeepRAG MDP 的核心动作（§2.4.1）。
 * <p>
 * 对每个查询/子问题独立判断是否需要检索外部知识：
 * <ul>
 *   <li>{@code retrieve} — 调用检索 Tool</li>
 *   <li>{@code parametric} — 直接用模型自身知识回答（零检索成本）</li>
 * </ul>
 * DeepRAG 实验表明检索尝试主要集中在 0-2 次，大多数查询可由模型自身知识回答。
 * <p>
 * 第一版为<b>软引导</b>：解析后仅记录（供观测 + 事件 emit），不强制改变控制流——
 * retrieve/parametric 仍由 LLM 是否调用检索 Tool 自然体现（设计文档 §2.6：LLM ReAct 隐式驱动）。
 *
 * @param subQuery  子问题原文（第一版查询分解未启用，默认为原始查询）
 * @param decision  决策：{@code "retrieve"} 或 {@code "parametric"}
 * @param reason    LLM 给出的决策理由（如"涉及具体技术细节，需要知识库文档支撑"）
 * @param fromTool  当决策为切换工具时，当前使用的检索工具名（可空）
 * @param toTool    当决策为切换工具时，建议切换到的检索工具名（可空）
 */
public record AtomicDecisionResult(
    String subQuery,
    String decision,
    String reason,
    String fromTool,
    String toTool
) {
    /** 决策取值集合 */
    public static final List<String> VALID_DECISIONS = List.of("retrieve", "parametric");

    /** 判断是否合法决策值 */
    public boolean isValid() {
        return decision != null && VALID_DECISIONS.contains(decision);
    }
}
