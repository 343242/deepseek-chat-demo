package com.smart.rag.infrastructure.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 思考参数方言解析器：{@link ThinkingConfig} + {@link ThinkingDialect} → 厂商要求的请求体字段。
 * <p>
 * <b>YAML 键名契约（强约束）</b>：{@code extractDefault} 读取 {@code reasoning-effort} /
 * {@code thinking-budget}（kebab-case）。Spring Boot {@code @ConfigurationProperties} 绑定
 * {@code params: Map<String,Object>} 时保留 YAML 原始键名，camelCase（{@code reasoningEffort}）
 * 会被静默忽略——配置时须用 kebab-case 键。
 * <p>
 * 未在 {@code params.thinking} 显式配置的候选，{@code extractDefault} 返回 {@code null}
 * （= 不注入任何思考参数，零行为变更）。
 */
public final class ThinkingBodyResolver {

    private ThinkingBodyResolver() {}

    /** Config + Dialect → 请求体字段（有序，key 稳定） */
    public static Map<String, Object> resolve(ThinkingConfig cfg, ThinkingDialect dialect) {
        var fields = new LinkedHashMap<String, Object>();
        if (dialect == ThinkingDialect.EFFORT) {
            fields.put("thinking", Map.of("type", cfg.enabled() ? "enabled" : "disabled"));
            if (cfg.enabled() && cfg.reasoningEffort() != null)
                fields.put("reasoning_effort", cfg.reasoningEffort());
        } else { // BUDGET
            fields.put("enable_thinking", cfg.enabled());
            if (cfg.enabled() && cfg.budgetTokens() != null)
                fields.put("thinking_budget", cfg.budgetTokens());
        }
        return fields;
    }

    /** 从 candidate.params 提取方言；未配默认 EFFORT */
    public static ThinkingDialect extractDialect(Map<String, Object> params) {
        Object t = params == null ? null : params.get("thinking");
        if (t instanceof Map<?, ?> m && m.get("dialect") instanceof String d)
            return "budget".equalsIgnoreCase(d) ? ThinkingDialect.BUDGET : ThinkingDialect.EFFORT;
        return ThinkingDialect.EFFORT;
    }

    /** 从 candidate.params 提取默认 ThinkingConfig；未配返回 null（= 不注入） */
    public static ThinkingConfig extractDefault(Map<String, Object> params) {
        Object t = params == null ? null : params.get("thinking");
        if (!(t instanceof Map<?, ?> m)) return null;
        ThinkingDialect d = extractDialect(params);
        boolean enabled = !(m.get("enabled") instanceof Boolean b) || b; // 默认 true
        if (d == ThinkingDialect.EFFORT) {
            Object e = m.get("reasoning-effort");
            return new ThinkingConfig(enabled, e instanceof String s ? s : null, null);
        }
        Object b = m.get("thinking-budget");
        Integer budget = b instanceof Number n ? n.intValue() : null;
        return new ThinkingConfig(enabled, null, budget);
    }
}
