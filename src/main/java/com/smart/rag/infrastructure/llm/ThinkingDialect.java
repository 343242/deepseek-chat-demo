package com.smart.rag.infrastructure.llm;

/**
 * 思考参数方言 —— 厂商请求体中思考参数的两种互不兼容的字段形态。
 * <p>
 * OpenAI 兼容端点下，思考参数存在两种"方言"：
 * <ul>
 *   <li>{@link #EFFORT}：{@code thinking.type} + {@code reasoning_effort}（DeepSeek、智谱 GLM）</li>
 *   <li>{@link #BUDGET}：{@code enable_thinking} + {@code thinking_budget}（百炼 Qwen）</li>
 * </ul>
 * 方言由候选 {@code params.thinking.dialect} 显式声明，不做按 provider/model 的自动推断——
 * 同一模型在不同聚合平台方言不同（如 GLM-5.2 在智谱用 EFFORT、在百炼用 BUDGET），自动推断脆弱。
 */
public enum ThinkingDialect {
    EFFORT,
    BUDGET
}
