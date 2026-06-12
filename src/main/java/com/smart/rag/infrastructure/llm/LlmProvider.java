package com.smart.rag.infrastructure.llm;

import com.smart.rag.infrastructure.llm.config.ProviderConfig;

/**
 * LLM 供应商接口
 * <p>
 * 供应商只负责"怎么连"和"怎么创建客户端"，不持有模型列表。
 * 模型配置独立管理（{@code ModelGroup} + {@code ModelCandidate}），通过 {@code provider} 字段引用供应商。
 * <p>
 * <b>职责</b>：
 * <ol>
 *   <li>识别自己是谁（id）</li>
 *   <li>提供连接配置（config → url / apiKey / endpoints）</li>
 *   <li>按候选模型声明创建对应的能力客户端（createClient）</li>
 * </ol>
 */
public interface LlmProvider {

    /** 供应商 id（对应 YAML providers key） */
    String id();

    /** 供应商连接配置（url、apiKey、endpoints） */
    ProviderConfig config();

    /**
     * 按候选模型声明创建能力客户端
     * <p>
     * 创建的是原始客户端（未包装 Resilience），
     * 由 {@code LlmClientRegistry} 在注册时统一包装 Resilient 装饰器。
     *
     * @param candidate 模型候选声明
     * @return 对应的能力客户端实例
     */
    CapabilityClient createClient(ModelCandidate candidate);
}
