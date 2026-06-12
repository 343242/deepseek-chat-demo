package com.smart.rag.infrastructure.llm.config;

import com.smart.rag.infrastructure.llm.LlmCapability;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * LLM 统一配置 — 对应 YAML {@code app.llm}
 * <p>
 * 聚合供应商、能力模型组、弹性策略三层配置。
 * <p>
 * <b>ModelCandidate YAML 绑定</b>：Spring Boot 无法自动区分子类型，
 * 需配合 {@code ModelCandidateConverter} 实现两阶段绑定。
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmConfig(
    /** 供应商连接配置（key = provider id，如 "bailian"、"deepseek"） */
    Map<String, ProviderConfig> providers,

    /** 能力模型组（key = 能力名小写：chat / embedding / reranking） */
    Map<String, ModelGroup> capabilities,

    /** 弹性策略配置 */
    @Nullable ResilienceConfig resilience
) {

    public LlmConfig {
        if (providers == null) providers = Map.of();
        if (capabilities == null) capabilities = Map.of();
    }

    /** 按能力获取模型组 */
    @Nullable
    public ModelGroup getCapabilityGroup(LlmCapability capability) {
        if (capabilities == null) return null;
        return capabilities.get(capability.name().toLowerCase());
    }

    /** 获取弹性配置（null-safe） */
    public ResilienceConfig resolveResilience() {
        return resilience != null ? resilience : new ResilienceConfig(null, null, null, null);
    }
}
