package com.smart.rag.infrastructure.llm.registry;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.fallback.FallbackEligibility;
import com.smart.rag.infrastructure.fallback.ModelCircuitBreakerRegistry;
import com.smart.rag.infrastructure.fallback.ProbeStreamHandler;
import com.smart.rag.infrastructure.fallback.probe.SharedProbeRegistry;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.LlmProvider;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.llm.config.CircuitBreakerProperties;
import com.smart.rag.infrastructure.llm.config.LlmConfig;
import com.smart.rag.infrastructure.llm.config.ModelGroup;
import com.smart.rag.infrastructure.llm.config.ProbeProperties;
import com.smart.rag.infrastructure.llm.config.ProviderConfig;
import com.smart.rag.infrastructure.llm.config.ResilienceConfig;
import com.smart.rag.infrastructure.llm.config.RetryConfig;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.infrastructure.llm.resilience.CircuitBreaker;
import com.smart.rag.infrastructure.llm.resilience.LlmCircuitBreakerAdapterRegistry;
import com.smart.rag.infrastructure.llm.resilience.ProbeHandler;
import com.smart.rag.infrastructure.llm.resilience.RetryPolicy;
import com.smart.rag.infrastructure.llm.strategy.CapabilityStrategy;
import com.smart.rag.infrastructure.llm.strategy.CapabilityStrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 客户端工厂 — 无状态，负责构建 RegistrySnapshot
 * <p>
 * 核心职责：
 * <ol>
 *   <li>遍历 YAML 配置的模型候选 → 通过 Provider + Strategy 创建原始客户端</li>
 *   <li>包装 Resilient 装饰器（重试 + 熔断 + 探测）</li>
 *   <li>构建不可变快照（clientsById + fallbackChains + defaultClients）</li>
 * </ol>
 */
@Component
public class LlmClientFactory {

    private static final Logger log = LoggerFactory.getLogger(LlmClientFactory.class);

    private final LlmConfig llmConfig;
    private final Map<String, LlmProvider> providers;
    private final CapabilityStrategyRegistry strategyRegistry;
    private final LlmCircuitBreakerAdapterRegistry circuitBreakerRegistry;
    private final FallbackEligibility fallbackEligibility;
    @Nullable
    private final ProbeStreamHandler probeStreamHandler;
    @Nullable
    private final SharedProbeRegistry sharedProbeRegistry;
    @Nullable
    private final LlmMetrics metrics;

    public LlmClientFactory(LlmConfig llmConfig,
                            Map<String, LlmProvider> providers,
                            CapabilityStrategyRegistry strategyRegistry,
                            LlmCircuitBreakerAdapterRegistry circuitBreakerRegistry,
                            FallbackEligibility fallbackEligibility,
                            @Nullable ProbeStreamHandler probeStreamHandler,
                            @Nullable SharedProbeRegistry sharedProbeRegistry,
                            @Nullable LlmMetrics metrics) {
        this.llmConfig = llmConfig;
        this.providers = providers;
        this.strategyRegistry = strategyRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.fallbackEligibility = fallbackEligibility;
        this.probeStreamHandler = probeStreamHandler;
        this.sharedProbeRegistry = sharedProbeRegistry;
        this.metrics = metrics;
    }

    /**
     * 构建完整的注册表快照
     * <p>
     * 遍历所有能力 → 模型组 → 候选，创建并包装客户端，组装为不可变快照。
     */
    public RegistrySnapshot buildSnapshot() {
        ResilienceConfig resilience = llmConfig.resolveResilience();

        Map<String, CapabilityClient> clientsById = new LinkedHashMap<>();
        Map<LlmCapability, List<CapabilityClient>> fallbackChains = new EnumMap<>(LlmCapability.class);
        Map<LlmCapability, String> defaultClients = new EnumMap<>(LlmCapability.class);
        Map<LlmCapability, String> deepThinkingClients = new EnumMap<>(LlmCapability.class);

        for (LlmCapability cap : LlmCapability.values()) {
            ModelGroup group = llmConfig.getCapabilityGroup(cap);
            if (group == null) continue;

            List<ModelCandidate> candidates = group.toModelCandidates(cap);
            List<CapabilityClient> chain = new ArrayList<>();

            for (ModelCandidate candidate : candidates) {
                if (!candidate.enabled()) continue;

                CapabilityClient rawClient = createRawClient(candidate);
                if (rawClient == null) continue;

                RetryConfig retryConfig = resilience.resolveRetryConfig(cap);
                CircuitBreakerProperties cbProps = resilience.resolveCircuitBreaker();
                ProbeProperties probeProps = resilience.resolveProbe();

                CapabilityClient wrapped = wrapWithResilience(rawClient, cap, retryConfig, cbProps, probeProps);

                clientsById.put(candidate.id(), wrapped);
                chain.add(wrapped);
            }

            if (!chain.isEmpty()) {
                fallbackChains.put(cap, Collections.unmodifiableList(chain));
            }

            // 默认模型
            if (group.getDefaultModel() != null) {
                defaultClients.put(cap, group.getDefaultModel());
            } else if (!chain.isEmpty()) {
                defaultClients.put(cap, chain.get(0).candidateId());
            }

            // Deep-thinking 模型
            if (group.getDeepThinkingModel() != null) {
                deepThinkingClients.put(cap, group.getDeepThinkingModel());
            }
        }

        log.info("LlmClientFactory: built snapshot with {} clients across {} capabilities",
            clientsById.size(), fallbackChains.size());

        return new RegistrySnapshot(
            Collections.unmodifiableMap(clientsById),
            Collections.unmodifiableMap(fallbackChains),
            Collections.unmodifiableMap(defaultClients),
            Collections.unmodifiableMap(deepThinkingClients),
            Set.of()
        );
    }

    /** 通过 Provider + Strategy 创建原始客户端 */
    @Nullable
    private CapabilityClient createRawClient(ModelCandidate candidate) {
        LlmProvider provider = providers.get(candidate.provider());
        if (provider == null) {
            log.warn("No provider '{}' registered for candidate '{}', skipping",
                candidate.provider(), candidate.id());
            return null;
        }
        if (!provider.config().isAvailable()) {
            log.warn("Provider '{}' not available (missing url/apiKey), skipping candidate '{}'",
                candidate.provider(), candidate.id());
            return null;
        }
        try {
            return provider.createClient(candidate);
        } catch (Exception e) {
            log.error("Failed to create client for candidate '{}': {}", candidate.id(), e.getMessage());
            return null;
        }
    }

    /** 包装 Resilient 装饰器 */
    private CapabilityClient wrapWithResilience(CapabilityClient raw,
                                                LlmCapability capability,
                                                RetryConfig retryConfig,
                                                CircuitBreakerProperties cbProps,
                                                ProbeProperties probeProps) {
        CapabilityStrategy strategy = strategyRegistry.get(capability);
        CircuitBreaker cb = circuitBreakerRegistry.getOrCreate(raw.candidateId());
        RetryPolicy retry = new RetryPolicy(retryConfig);

        ProbeHandler probe = null;
        if (probeStreamHandler != null && probeProps != null && probeProps.enabled() != null && probeProps.enabled()) {
            long timeout = probeProps.probeTimeoutMs() != null ? probeProps.probeTimeoutMs() : 3000L;
            probe = new ProbeHandler(probeStreamHandler, sharedProbeRegistry, timeout);
        }

        return strategy.wrapWithResilience(raw, cb, retry, probe, metrics);
    }
}
