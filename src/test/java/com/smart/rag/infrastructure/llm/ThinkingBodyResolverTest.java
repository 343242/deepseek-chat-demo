package com.smart.rag.infrastructure.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ThinkingBodyResolver} 单测 —— AC1/AC2/AC3/AC4/AC9 + YAML 键名契约。
 */
@DisplayName("ThinkingBodyResolver 方言解析与请求体映射")
class ThinkingBodyResolverTest {

    // ====== resolve：EFFORT 方言 ======

    @Test
    @DisplayName("EFFORT 开启 + reasoning-effort：thinking.type=enabled + reasoning_effort")
    void resolveEffortEnabledWithEffort() {
        Map<String, Object> body = ThinkingBodyResolver.resolve(
            ThinkingConfig.effort("high"), ThinkingDialect.EFFORT);

        assertThat(body).containsExactlyInAnyOrderEntriesOf(Map.of(
            "thinking", Map.of("type", "enabled"),
            "reasoning_effort", "high"));
    }

    @Test
    @DisplayName("EFFORT 开启无 effort：仅 thinking.type=enabled，effort 由厂商默认（AC1 未配场景）")
    void resolveEffortEnabledWithoutEffort() {
        Map<String, Object> body = ThinkingBodyResolver.resolve(
            new ThinkingConfig(true, null, null), ThinkingDialect.EFFORT);

        assertThat(body).isEqualTo(Map.of("thinking", Map.of("type", "enabled")));
        assertThat(body).doesNotContainKey("reasoning_effort");
    }

    @Test
    @DisplayName("EFFORT 关闭：thinking.type=disabled，无 reasoning_effort（AC9）")
    void resolveEffortDisabled() {
        Map<String, Object> body = ThinkingBodyResolver.resolve(
            ThinkingConfig.disabled(), ThinkingDialect.EFFORT);

        assertThat(body).isEqualTo(Map.of("thinking", Map.of("type", "disabled")));
        assertThat(body).doesNotContainKey("reasoning_effort");
    }

    // ====== resolve：BUDGET 方言 ======

    @Test
    @DisplayName("BUDGET 开启 + thinking-budget：enable_thinking=true + thinking_budget（AC2）")
    void resolveBudgetEnabledWithBudget() {
        Map<String, Object> body = ThinkingBodyResolver.resolve(
            ThinkingConfig.budgeted(16000), ThinkingDialect.BUDGET);

        assertThat(body).containsExactlyInAnyOrderEntriesOf(Map.of(
            "enable_thinking", true,
            "thinking_budget", 16000));
    }

    @Test
    @DisplayName("BUDGET 关闭：enable_thinking=false，无 thinking_budget（AC9）")
    void resolveBudgetDisabled() {
        Map<String, Object> body = ThinkingBodyResolver.resolve(
            ThinkingConfig.disabled(), ThinkingDialect.BUDGET);

        assertThat(body).isEqualTo(Map.of("enable_thinking", false));
        assertThat(body).doesNotContainKey("thinking_budget");
    }

    // ====== extractDialect ======

    @Test
    @DisplayName("dialect=budget（忽略大小写）→ BUDGET")
    void dialectBudgetCaseInsensitive() {
        assertThat(ThinkingBodyResolver.extractDialect(Map.of(
            "thinking", Map.of("dialect", "Budget")))).isEqualTo(ThinkingDialect.BUDGET);
    }

    @Test
    @DisplayName("dialect 未配 / 未知值 / params 为 null → 默认 EFFORT")
    void dialectDefaultsToEffort() {
        assertThat(ThinkingBodyResolver.extractDialect(Map.of(
            "thinking", Map.of("enabled", true)))).isEqualTo(ThinkingDialect.EFFORT);
        assertThat(ThinkingBodyResolver.extractDialect(Map.of(
            "thinking", Map.of("dialect", "foo")))).isEqualTo(ThinkingDialect.EFFORT);
        assertThat(ThinkingBodyResolver.extractDialect(null)).isEqualTo(ThinkingDialect.EFFORT);
        assertThat(ThinkingBodyResolver.extractDialect(Map.of())).isEqualTo(ThinkingDialect.EFFORT);
    }

    // ====== extractDefault ======

    @Test
    @DisplayName("未配 params.thinking → 返回 null（= 不注入，AC3/AC4 回落）")
    void extractDefaultAbsentReturnsNull() {
        assertThat(ThinkingBodyResolver.extractDefault(null)).isNull();
        assertThat(ThinkingBodyResolver.extractDefault(Map.of())).isNull();
        assertThat(ThinkingBodyResolver.extractDefault(Map.of("temperature", 0.7))).isNull();
    }

    @Test
    @DisplayName("EFFORT 默认：enabled 缺省为 true，kebab-case reasoning-effort 生效")
    void extractDefaultEffort() {
        ThinkingConfig cfg = ThinkingBodyResolver.extractDefault(Map.of(
            "thinking", Map.of("dialect", "effort", "reasoning-effort", "max")));

        assertThat(cfg).isNotNull();
        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.reasoningEffort()).isEqualTo("max");
        assertThat(cfg.budgetTokens()).isNull();
    }

    @Test
    @DisplayName("EFFORT enabled=false → 关闭思考")
    void extractDefaultEffortDisabled() {
        ThinkingConfig cfg = ThinkingBodyResolver.extractDefault(Map.of(
            "thinking", Map.of("enabled", false, "reasoning-effort", "high")));

        assertThat(cfg).isNotNull();
        assertThat(cfg.enabled()).isFalse();
    }

    @Test
    @DisplayName("BUDGET 默认：kebab-case thinking-budget 生效")
    void extractDefaultBudget() {
        ThinkingConfig cfg = ThinkingBodyResolver.extractDefault(Map.of(
            "thinking", Map.of("dialect", "budget", "thinking-budget", 8000)));

        assertThat(cfg).isNotNull();
        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.budgetTokens()).isEqualTo(8000);
        assertThat(cfg.reasoningEffort()).isNull();
    }

    @Test
    @DisplayName("YAML 键名契约：camelCase reasoningEffort 被静默忽略（防配置失效）")
    void extractDefaultCamelCaseKeysSilentlyIgnored() {
        ThinkingConfig cfg = ThinkingBodyResolver.extractDefault(Map.of(
            "thinking", Map.of("dialect", "effort", "reasoningEffort", "high")));

        assertThat(cfg).isNotNull();
        assertThat(cfg.reasoningEffort()).isNull(); // camelCase 不绑定，必须用 kebab-case
    }
}
