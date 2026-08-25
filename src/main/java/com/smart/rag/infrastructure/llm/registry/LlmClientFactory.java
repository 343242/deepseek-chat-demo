package com.smart.rag.infrastructure.llm.registry;

import com.smart.rag.infrastructure.fallback.ProbeStreamHandler;
import com.smart.rag.infrastructure.fallback.probe.SharedProbeRegistry;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.LlmProvider;
import com.smart.rag.infrastructure.llm.ModelCandidate;
import com.smart.rag.infrastructure.fallback.CircuitBreakerProperties;
import com.smart.rag.infrastructure.llm.config.LlmConfig;
import com.smart.rag.infrastructure.llm.config.ModelGroup;
import com.smart.rag.infrastructure.llm.config.ProbeProperties;
import com.smart.rag.infrastructure.llm.config.ConcurrencyConfig;
import com.smart.rag.infrastructure.llm.config.ResilienceConfig;
import com.smart.rag.infrastructure.llm.config.RetryConfig;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.infrastructure.llm.resilience.AdmissionControl;
import com.smart.rag.infrastructure.llm.resilience.AdmissionControlRegistry;
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

    private static final long DEFAULT_PROBE_TIMEOUT_MS = 3000L;

    private final LlmConfig llmConfig;
    private final Map<String, LlmProvider> providers;
    private final CapabilityStrategyRegistry strategyRegistry;
    private final LlmCircuitBreakerAdapterRegistry circuitBreakerRegistry;
    private final AdmissionControlRegistry admissionControlRegistry;
    @Nullable
    private final ProbeStreamHandler probeStreamHandler;
    @Nullable
    private final SharedProbeRegistry sharedProbeRegistry;
    @Nullable
    private final LlmMetrics metrics;

    /** Cached singleton ProbeHandler — created once (stateless after construction) */
    @Nullable
    private volatile ProbeHandler cachedProbeHandler;

    public LlmClientFactory(LlmConfig llmConfig,
                            Map<String, LlmProvider> providers,
                            CapabilityStrategyRegistry strategyRegistry,
                            LlmCircuitBreakerAdapterRegistry circuitBreakerRegistry,
                            AdmissionControlRegistry admissionControlRegistry,
                            @Nullable ProbeStreamHandler probeStreamHandler,
                            @Nullable SharedProbeRegistry sharedProbeRegistry,
                            @Nullable LlmMetrics metrics) {
        this.llmConfig = llmConfig;
        this.providers = providers.values().stream()
            .collect(Collectors.toMap(
                LlmProvider::id, p -> p,
                (a, b) -> { throw new IllegalStateException(
                    "Duplicate provider id '" + a.id()
                    + "': " + a.getClass().getName() + " vs " + b.getClass().getName()); }));
        this.strategyRegistry = strategyRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.admissionControlRegistry = admissionControlRegistry;
        this.probeStreamHandler = probeStreamHandler;
        this.sharedProbeRegistry = sharedProbeRegistry;
        this.metrics = metrics;
    }

    /** (candidate, provider) pair — buildChain 共用 */
    private record CandidateProvider(ModelCandidate candidate, LlmProvider provider) {
        LlmCapability capability() { return candidate.capability(); }
    }

    /**
     * 构建完整的注册表快照（系统级 yml 链路，作 fallback 底座）。
     * <p>
     * 遍历所有能力 → 模型组 → 候选，创建并包装客户端，组装为不可变快照。
     */
    public RegistrySnapshot buildSnapshot() {
        ResilienceConfig resilience = llmConfig.resolveResilience();

        Map<String, CapabilityClient> clientsById = new LinkedHashMap<>();
        Map<LlmCapability, List<CapabilityClient>> fallbackChains = new EnumMap<>(LlmCapability.class);
        Map<LlmCapability, String> defaultClients = new EnumMap<>(LlmCapability.class);
        Map<LlmCapability, String> deepThinkingClients = new EnumMap<>(LlmCapability.class);

        // 收集 yml candidates + 对应 provider
        List<CandidateProvider> items = new ArrayList<>();
        for (LlmCapability cap : LlmCapability.values()) {
            ModelGroup group = llmConfig.getCapabilityGroup(cap);
            if (group == null) continue;
            for (ModelCandidate candidate : group.toModelCandidates(cap)) {
                LlmProvider provider = providers.get(candidate.provider());
                if (provider == null) {
                    log.warn("No provider '{}' registered for candidate '{}', skipping",
                        candidate.provider(), candidate.id());
                    continue;
                }
                items.add(new CandidateProvider(candidate, provider));
            }
        }
        buildChain(items, resilience, clientsById, fallbackChains);

        // 默认模型 / Deep-thinking（yml group 配置）
        for (LlmCapability cap : LlmCapability.values()) {
            ModelGroup group = llmConfig.getCapabilityGroup(cap);
            if (group == null) continue;
            List<CapabilityClient> chain = fallbackChains.getOrDefault(cap, List.of());

            String defaultId = group.getDefaultModel();
            if (defaultId != null) {
                if (!clientsById.containsKey(defaultId)) {
                    throw new IllegalStateException(
                        cap + ".default-model '" + defaultId + "' references unknown candidate");
                }
                defaultClients.put(cap, defaultId);
            } else if (!chain.isEmpty()) {
                defaultClients.put(cap, chain.get(0).candidateId());
            }

            if (group.getDeepThinkingModel() != null) {
                if (!clientsById.containsKey(group.getDeepThinkingModel())) {
                    throw new IllegalStateException(
                        cap + ".deep-thinking-model '" + group.getDeepThinkingModel()
                        + "' references unknown candidate");
                }
                deepThinkingClients.put(cap, group.getDeepThinkingModel());
            }
        }

        log.info("LlmClientFactory: built snapshot with {} clients across {} capabilities",
            clientsById.size(), fallbackChains.size());

        return new RegistrySnapshot(
            clientsById,
            fallbackChains,
            defaultClients,
            deepThinkingClients,
            Map.of(),
            Set.of()
        );
    }

    /**
     * 通用：遍历 (candidate, provider) pairs → createRawClient → wrapWithResilience → 填充 clientsById / fallbackChains。
     * <p>
     * chains 末尾 freeze 为 unmodifiable。
     */
    private void buildChain(List<CandidateProvider> items, ResilienceConfig resilience,
                            Map<String, CapabilityClient> clientsById,
                            Map<LlmCapability, List<CapabilityClient>> fallbackChains) {
        for (CandidateProvider item : items) {
            LlmCapability cap = item.capability();
            CapabilityClient raw = createRawClient(item.provider(), item.candidate());
            if (raw == null) continue;

            RetryConfig retryConfig = resilience.resolveRetryConfig(cap);
            ConcurrencyConfig concurrencyConfig = resilience.resolveConcurrencyConfig(cap);
            CircuitBreakerProperties cbProps = resilience.resolveCircuitBreaker();
            ProbeProperties probeProps = resilience.resolveProbe();
            CapabilityClient wrapped = wrapWithResilience(raw, item.candidate(), cap,
                retryConfig, concurrencyConfig, cbProps, probeProps);

            clientsById.put(item.candidate().id(), wrapped);
            fallbackChains.computeIfAbsent(cap, k -> new ArrayList<>()).add(wrapped);
        }
        fallbackChains.replaceAll((cap, chain) -> Collections.unmodifiableList(chain));
    }

    /** 通过 Provider 创建原始客户端（provider 由 yml providers map 解析） */
    @Nullable
    private CapabilityClient createRawClient(LlmProvider provider, ModelCandidate candidate) {
        if (!provider.config().isAvailable()) {
            log.warn("Provider '{}' not available (missing url/apiKey), skipping candidate '{}'",
                provider.id(), candidate.id());
            return null;
        }
        try {
            return provider.createClient(candidate);
        } catch (Exception e) {
            log.error("Failed to create client for candidate '{}'", candidate.id(), e);
            if (metrics != null) metrics.recordClientInitFailure(candidate.id());
            return null;
        }
    }

    /** 包装 Resilient 装饰器 */
    private CapabilityClient wrapWithResilience(CapabilityClient raw,
                                                ModelCandidate candidate,
                                                LlmCapability capability,
                                                RetryConfig retryConfig,
                                                ConcurrencyConfig concurrencyConfig,
                                                CircuitBreakerProperties cbProps,
                                                ProbeProperties probeProps) {
        CapabilityStrategy strategy = strategyRegistry.get(capability);
        CircuitBreaker cb = circuitBreakerRegistry.getOrCreate(raw.candidateId());
        RetryPolicy retry = new RetryPolicy(retryConfig);

        // 并发准入闸门（WS4，仅系统链路——用户自带 key 链路已随 WS0 移除，天然不进闸门，决策 14）：
        // 三级解析 candidate params.max-concurrent（终态覆盖，仅数量）> capability override > global
        AdmissionControl admission = resolveAdmissionControl(raw.candidateId(),
            concurrencyConfig, candidate.params());

        ProbeHandler probe = getOrCreateProbeHandler(probeProps);

        return strategy.wrapWithResilience(raw, cb, retry, probe, metrics, admission);
    }

    /** 三级解析闸门配置并经注册表获取（复用/替换语义见 AdmissionControlRegistry） */
    private AdmissionControl resolveAdmissionControl(String candidateId,
                                                      ConcurrencyConfig config,
                                                      Map<String, Object> candidateParams) {
        ConcurrencyConfig effective = config;
        Object perCandidate = candidateParams != null ? candidateParams.get("max-concurrent") : null;
        if (perCandidate instanceof Number n && n.intValue() > 0) {
            effective = new ConcurrencyConfig(n.intValue(), config.acquireTimeoutMs());
        }
        return admissionControlRegistry.getOrCreate(candidateId, effective);
    }

    /**
     * 获取或创建缓存的 ProbeHandler 单例。
     * <p>
     * ProbeHandler 在构造后无状态（仅持有 final 依赖），可被多 candidate 安全共享。
     * 首次调用时根据 ProbeProperties 决定是否启用，后续 candidate 复用同一实例。
     */
    @Nullable
    private ProbeHandler getOrCreateProbeHandler(@Nullable ProbeProperties probeProps) {
        if (!isProbeEnabled(probeProps)) return null;
        ProbeHandler existing = cachedProbeHandler;
        if (existing != null) return existing;
        synchronized (this) {
            if (cachedProbeHandler == null) {
                cachedProbeHandler = new ProbeHandler(probeStreamHandler, sharedProbeRegistry,
                    resolveProbeTimeout(probeProps));
            }
            return cachedProbeHandler;
        }
    }

    /** Check whether probe wrapping is applicable（首包探测恒启用，仅需 handler 与配置就位） */
    private boolean isProbeEnabled(@Nullable ProbeProperties probeProps) {
        return probeStreamHandler != null
            && probeProps != null;
    }

    /** Resolve probe timeout, falling back to default */
    private long resolveProbeTimeout(@Nullable ProbeProperties probeProps) {
        return probeProps != null && probeProps.probeTimeoutMs() != null
            ? probeProps.probeTimeoutMs()
            : DEFAULT_PROBE_TIMEOUT_MS;
    }
}
