package com.smart.rag.infrastructure.llm.registry;

import com.smart.rag.infrastructure.concurrent.ScopeOptions;
import com.smart.rag.infrastructure.concurrent.ScopePolicy;
import com.smart.rag.infrastructure.concurrent.ScopeTimeoutException;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.concurrent.TaskScope;
import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.infrastructure.llm.resilience.AdmissionControlRegistry;
import com.smart.rag.infrastructure.llm.resilience.LlmCircuitBreakerAdapterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM 客户端注册表 — 纯系统级，无锁读写分离。
 * <p>
 * {@link AtomicReference}&lt;{@link RegistrySnapshot}&gt; 持有 yml 全员共享的系统快照：
 * 系统客户端 + fallback 链 + 禁用集；配置变更经 {@link #refresh()} 整体替换。
 * <p>
 * <b>资源管理</b>：refresh 时关闭不再存在的旧 client；@PreDestroy 并发排空系统客户端。
 */
@Component
public class LlmClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmClientRegistry.class);

    private static final Duration DEFAULT_DESTROY_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_DESTROY_CONCURRENCY = 8;

    private final LlmClientFactory factory;
    private final ScopedTasks scopedTasks;
    private final AdmissionControlRegistry admissionControlRegistry;
    private final LlmCircuitBreakerAdapterRegistry circuitBreakerRegistry;
    private final Duration destroyTimeout;
    private final int destroyConcurrency;
    private final AtomicReference<RegistrySnapshot> snapshotRef;

    @Autowired
    public LlmClientRegistry(LlmClientFactory factory, ScopedTasks scopedTasks,
                             AdmissionControlRegistry admissionControlRegistry,
                             LlmCircuitBreakerAdapterRegistry circuitBreakerRegistry) {
        this.factory = factory;
        this.scopedTasks = scopedTasks;
        this.admissionControlRegistry = admissionControlRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.destroyTimeout = DEFAULT_DESTROY_TIMEOUT;
        this.destroyConcurrency = DEFAULT_DESTROY_CONCURRENCY;
        this.snapshotRef = new AtomicReference<>(RegistrySnapshot.empty());
    }

    // ======================== Lifecycle ========================

    @PostConstruct
    public void init() {
        refresh();
        int size = snapshotRef.get().size();
        if (size == 0) {
            log.warn("LlmClientRegistry initialized with 0 clients — check app.llm configuration");
        } else {
            log.info("LlmClientRegistry initialized: {} clients registered", size);
        }
    }

    @PreDestroy
    public void destroy() {
        RegistrySnapshot snapshot = snapshotRef.getAndSet(RegistrySnapshot.empty());

        // 闸门/熔断 adapter 注册表同步清理（决策 19：destroy 挂接，AC10 无僵尸序列）
        snapshot.clientsById().forEach((id, client) -> {
            admissionControlRegistry.evictQuietly(id);
            circuitBreakerRegistry.evictQuietly(id);
        });

        ScopeOptions options = ScopeOptions.builder("llm-registry-destroy")
            .policy(ScopePolicy.COLLECT_ALL)
            .maxConcurrency(destroyConcurrency)
            .build();

        int systemTotal = snapshot.clientsById().size();
        if (systemTotal > 0) {
            try (TaskScope scope = scopedTasks.open("llm-registry-destroy", options)) {
                snapshot.clientsById().forEach((id, client) -> {
                    scope.fork("close-" + id, () -> {
                        try { client.close(); } catch (Exception e) { log.warn("Failed to close client {}: {}", id, e.getMessage()); }
                        return null;
                    });
                });
                try {
                    scope.joinUntil(destroyTimeout);
                } catch (ScopeTimeoutException e) {
                    log.warn("LlmClientRegistry destroy timed out after {} — system clients may still be closing", destroyTimeout);
                }
            }
        }
        log.info("LlmClientRegistry destroyed ({} system clients)", systemTotal);
    }

    /** 重新构建系统级快照（配置变更时调用） */
    public void refresh() {
        Set<String> preservedDisabled = snapshotRef.get().disabledSet();
        RegistrySnapshot fresh = factory.buildSnapshot();
        RegistrySnapshot newSnapshot = fresh.withDisabledSet(preservedDisabled);
        RegistrySnapshot old = snapshotRef.getAndSet(newSnapshot);
        if (old != null) {
            old.clientsById().forEach((id, client) -> {
                if (!newSnapshot.clientsById().containsKey(id)) {
                    // 候选消失：旁路 evict 闸门 + 熔断 adapter（决策 19，顺带修复熔断器注册表既有缺口）
                    admissionControlRegistry.evictQuietly(id);
                    circuitBreakerRegistry.evictQuietly(id);
                    try { client.close(); } catch (Exception e) {
                        log.warn("Failed to close old client {}: {}", id, e.getMessage());
                    }
                }
            });
        }
        log.info("Registry refreshed: {} clients ({} disabled preserved)",
            newSnapshot.size(), preservedDisabled.size());
    }

    // ======================== Query API（系统级）========================

    public CapabilityClient get(String candidateId) {
        CapabilityClient client = snapshotRef.get().getClient(candidateId);
        if (client == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "No client registered for candidate: " + candidateId);
        }
        return client;
    }

    public CapabilityClient find(String candidateId) {
        return snapshotRef.get().getClient(candidateId);
    }

    public <T extends CapabilityClient> T get(String candidateId, Class<T> type) {
        CapabilityClient client = get(candidateId);
        if (!type.isInstance(client)) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "Client '" + candidateId + "' does not implement " + type.getSimpleName());
        }
        return type.cast(client);
    }

    public CapabilityClient getDefault(LlmCapability capability) {
        CapabilityClient client = snapshotRef.get().getDefaultClient(capability);
        if (client == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "No default client for capability: " + capability);
        }
        return client;
    }

    public <T extends CapabilityClient> T getDefault(LlmCapability capability, Class<T> type) {
        CapabilityClient client = getDefault(capability);
        if (!type.isInstance(client)) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "Default client for " + capability + " does not implement " + type.getSimpleName());
        }
        return type.cast(client);
    }

    public CapabilityClient getDeepThinking(LlmCapability capability) {
        CapabilityClient client = snapshotRef.get().getDeepThinkingClient(capability);
        if (client == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "No deep-thinking client for capability: " + capability);
        }
        return client;
    }

    public List<CapabilityClient> getChain(LlmCapability capability) {
        return snapshotRef.get().getChain(capability);
    }

    public Set<String> registeredCandidateIds() {
        return snapshotRef.get().clientsById().keySet();
    }

    // ======================== Runtime Control ========================

    public void disable(String candidateId) {
        snapshotRef.updateAndGet(current -> {
            Set<String> newDisabled = new java.util.LinkedHashSet<>(current.disabledSet());
            if (newDisabled.add(candidateId)) {
                log.info("Disabled candidate: {}", candidateId);
            }
            return current.withDisabledSet(Set.copyOf(newDisabled));
        });
    }

    public void enable(String candidateId) {
        snapshotRef.updateAndGet(current -> {
            Set<String> newDisabled = new java.util.LinkedHashSet<>(current.disabledSet());
            if (newDisabled.remove(candidateId)) {
                log.info("Enabled candidate: {}", candidateId);
            }
            return current.withDisabledSet(Set.copyOf(newDisabled));
        });
    }

    public RegistrySnapshot snapshot() {
        return snapshotRef.get();
    }
}
