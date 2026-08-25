package com.smart.rag.infrastructure.llm.strategy;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.config.ProviderConfig;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.infrastructure.llm.resilience.AdmissionControl;
import com.smart.rag.infrastructure.llm.resilience.CircuitBreaker;
import com.smart.rag.infrastructure.llm.resilience.ProbeHandler;
import com.smart.rag.infrastructure.llm.resilience.RetryPolicy;
import org.springframework.lang.Nullable;

/**
 * 能力策略 — 封装特定能力的客户端创建与 Resilient 包装逻辑。
 * <p>
 * 每种 {@link LlmCapability} 注册一个实现，
 * 新增能力只需添加策略 Bean，无需修改 Provider 或 Registry 中的 switch。
 * <p>
 * 注册方式：{@code @Component} + Spring 自动收集，或手动注册到
 * {@link CapabilityStrategyRegistry}。
 */
public interface CapabilityStrategy {

    /** 此策略负责的能力类型 */
    LlmCapability capability();

    /**
     * 端点选择：从 ProviderConfig 中取出此能力对应的 endpoint。
     */
    String resolveEndpoint(ProviderConfig config);

    /**
     * 客户端创建：基于 Provider 解析后的连接参数 + candidate 构建原始 CapabilityClient。
     */
    CapabilityClient createClient(String baseUrl, String endpoint,
                                  String apiKey, ModelCandidate candidate);

    /** Resilient 包装：将原始 Client 包装为带重试/熔断/并发闸门的装饰器（WS4） */
    CapabilityClient wrapWithResilience(CapabilityClient raw,
                                        CircuitBreaker circuitBreaker,
                                        RetryPolicy retryPolicy,
                                        @Nullable ProbeHandler probeHandler,
                                        @Nullable LlmMetrics metrics,
                                        @Nullable AdmissionControl admissionControl);
}
