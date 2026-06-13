package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.ModelCandidate;

/**
 * 供应商专用客户端工厂 — CapabilityStrategy 的扩展点
 * <p>
 * 用于供应商（如百炼）的特定能力需要使用原生 API 而非 OpenAI 兼容 API 的场景。
 * 通过 {@code @Component} 自注册，Strategy 在 {@code createClient()} 时自动发现。
 * <p>
 * <b>查找键</b>：{@code providerId() + ":" + capability()} 组合唯一确定一个工厂。
 * 同一供应商的不同能力可以注册不同的工厂（如百炼 Embedding 和 Reranking 各自独立）。
 */
public interface ProviderClientFactory {

    /** 此工厂负责的供应商 id（对应 YAML providers key） */
    String providerId();

    /** 此工厂负责的能力类型 */
    LlmCapability capability();

    /**
     * 创建专用客户端（使用原生 API）
     *
     * @param baseUrl   供应商基础 URL
     * @param endpoint  能力对应的端点路径（从配置解析）
     * @param apiKey    供应商 API Key
     * @param candidate 模型候选声明
     * @return 专用能力客户端（已实现对应的 CapabilityClient 子接口）
     */
    CapabilityClient create(String baseUrl, String endpoint, String apiKey, ModelCandidate candidate);
}
