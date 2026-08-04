package com.smart.rag.infrastructure.llm;

/**
 * 归一化思考意图（厂商无关）——承载于 {@link ChatRequest}，支持每请求覆盖候选默认。
 * <p>
 * 联合 record 承载 EFFORT + BUDGET 两种方言的参数：{@code reasoningEffort} 仅
 * EFFORT 方言使用，{@code budgetTokens} 仅 BUDGET 方言使用，另一方言忽略。
 * 方言由 {@link ThinkingDialect} 决定。
 */
public record ThinkingConfig(
    boolean enabled,
    String reasoningEffort,   // max|xhigh|high|medium|low|minimal|none；BUDGET 方言忽略
    Integer budgetTokens      // token 上限；EFFORT 方言忽略
) {
    public static ThinkingConfig disabled() {
        return new ThinkingConfig(false, null, null);
    }

    public static ThinkingConfig effort(String effort) {
        return new ThinkingConfig(true, effort, null);
    }

    public static ThinkingConfig budgeted(int tokens) {
        return new ThinkingConfig(true, null, tokens);
    }
}
