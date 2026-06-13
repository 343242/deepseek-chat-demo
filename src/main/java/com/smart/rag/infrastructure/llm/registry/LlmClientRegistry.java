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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM 客户端注册表 — 无锁读写分离
 * <p>
 * 核心设计：
 * <ul>
 *   <li>所有读操作直接读取 {@link AtomicReference} 中的不可变 {@link RegistrySnapshot}</li>
 *   <li>写操作（refresh / disable / enable）构建新快照后 CAS 替换</li>
 *   <li>禁用/启用通过 copy-on-write 修改 disabledSet</li>
 * </ul>
 * <p>
 * <b>查询 API</b>：
 * <ul>
 *   <li>{@code get(candidateId, type)} — 按 ID + 能力接口获取客户端</li>
 *   <li>{@code getDefault(type)} — 获取能力默认客户端</li>
 *   <li>{@code getChain(capability)} — 获取 Fallback Chain</li>
 * </ul>
 */
@Component
public class LlmClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmClientRegistry.class);

    /** Default global timeout for parallel close on registry destroy */
    private static final Duration DEFAULT_DESTROY_TIMEOUT = Duration.ofSeconds(30);

    /** Default max parallelism for client close operations */
    private static final int DEFAULT_DESTROY_CONCURRENCY = 8;

    private final LlmClientFactory factory;
    private final ScopedTasks scopedTasks;
    private final Duration destroyTimeout;
    private final int destroyConcurrency;
    private final AtomicReference<RegistrySnapshot> snapshotRef;

    /**
     * @param factory             客户端工厂
     * @param scopedTasks         结构化并发引擎
     * @param destroyTimeout      销毁时并行关闭的总超时（null 使用默认 30s）
     * @param destroyConcurrency  销毁时并行关闭的最大并发（null 使用默认 8）
     */
    public LlmClientRegistry(LlmClientFactory factory, ScopedTasks scopedTasks,
                              @Nullable Duration destroyTimeout,
                              @Nullable Integer destroyConcurrency) {
        this.factory = factory;
        this.scopedTasks = scopedTasks;
        this.destroyTimeout = destroyTimeout != null ? destroyTimeout : DEFAULT_DESTROY_TIMEOUT;
        this.destroyConcurrency = destroyConcurrency != null ? destroyConcurrency : DEFAULT_DESTROY_CONCURRENCY;
        this.snapshotRef = new AtomicReference<>(RegistrySnapshot.empty());
    }

    /**
     * 兼容构造器：使用默认超时和并发参数。
     * 保留以支持现有调用方与单元测试（不显式提供 destroy 参数时）。
     * <p>
     * 当 Spring 注入本组件时，由于本类有多个构造器，必须显式标注 {@link Autowired}
     * 指定优先使用本构造器（仅依赖 {@code LlmClientFactory} 和 {@code ScopedTasks} 两个 bean）。
     * 需要自定义 destroy 参数的场景应通过 Java 配置类手动 new（绕过组件扫描）。
     */
    @Autowired
    public LlmClientRegistry(LlmClientFactory factory, ScopedTasks scopedTasks) {
        this(factory, scopedTasks, null, null);
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
        if (snapshot.clientsById().isEmpty()) {
            log.info("LlmClientRegistry destroyed (no clients)");
            return;
        }

        ScopeOptions options = ScopeOptions.builder("llm-registry-destroy")
            .policy(ScopePolicy.COLLECT_ALL)
            .maxConcurrency(destroyConcurrency)
            .build();

        int total = snapshot.clientsById().size();
        try (TaskScope scope = scopedTasks.open("llm-registry-destroy", options)) {
            snapshot.clientsById().forEach((id, client) -> {
                scope.fork("close-" + id, () -> {
                    try {
                        client.close();
                    } catch (Exception e) {
                        log.warn("Failed to close client {}: {}", id, e.getMessage());
                    }
                    return null;
                });
            });
            try {
                scope.joinUntil(destroyTimeout);
            } catch (ScopeTimeoutException e) {
                log.warn("LlmClientRegistry destroy timed out after {} — {} clients may still be closing",
                    destroyTimeout, total);
            }
        }
        log.info("LlmClientRegistry destroyed ({} clients closed in parallel)", total);
    }

    /** 重新构建快照（配置变更时调用） */
    public void refresh() {
        // Preserve runtime disabledSet across refresh
        Set<String> preservedDisabled = snapshotRef.get().disabledSet();
        RegistrySnapshot fresh = factory.buildSnapshot();
        RegistrySnapshot newSnapshot = fresh.withDisabledSet(preservedDisabled);
        RegistrySnapshot old = snapshotRef.getAndSet(newSnapshot);
        // Close old clients that are no longer in the new snapshot
        if (old != null) {
            old.clientsById().forEach((id, client) -> {
                if (!newSnapshot.clientsById().containsKey(id)) {
                    try { client.close(); } catch (Exception e) {
                        log.warn("Failed to close old client {}: {}", id, e.getMessage());
                    }
                }
            });
        }
        log.info("Registry refreshed: {} clients ({} disabled preserved)",
            newSnapshot.size(), preservedDisabled.size());
    }

    // ======================== Query API ========================

    /** 按 candidateId 获取客户端 */
    public CapabilityClient get(String candidateId) {
        CapabilityClient client = snapshotRef.get().getClient(candidateId);
        if (client == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "No client registered for candidate: " + candidateId);
        }
        return client;
    }

    /** 按 candidateId 获取客户端，返回 null 而非抛异常 */
    public CapabilityClient find(String candidateId) {
        return snapshotRef.get().getClient(candidateId);
    }

    /** 按 candidateId + 目标类型获取客户端 */
    @SuppressWarnings("unchecked")
    public <T extends CapabilityClient> T get(String candidateId, Class<T> type) {
        CapabilityClient client = get(candidateId);
        if (!type.isInstance(client)) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "Client '" + candidateId + "' does not implement " + type.getSimpleName());
        }
        return (T) client;
    }

    /** 获取指定能力的默认客户端 */
    public CapabilityClient getDefault(LlmCapability capability) {
        CapabilityClient client = snapshotRef.get().getDefaultClient(capability);
        if (client == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "No default client for capability: " + capability);
        }
        return client;
    }

    /** 获取指定能力的默认客户端（类型安全） */
    @SuppressWarnings("unchecked")
    public <T extends CapabilityClient> T getDefault(LlmCapability capability, Class<T> type) {
        CapabilityClient client = getDefault(capability);
        if (!type.isInstance(client)) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "Default client for " + capability + " does not implement " + type.getSimpleName());
        }
        return (T) client;
    }

    /** 获取指定能力的 deep-thinking 客户端 */
    public CapabilityClient getDeepThinking(LlmCapability capability) {
        CapabilityClient client = snapshotRef.get().getDeepThinkingClient(capability);
        if (client == null) {
            throw new RemoteException(RemoteErrorCode.LLM_CONFIG_ERROR,
                "No deep-thinking client for capability: " + capability);
        }
        return client;
    }

    /** 获取 Fallback Chain（按 priority 排序，排除禁用的） */
    public List<CapabilityClient> getChain(LlmCapability capability) {
        return snapshotRef.get().getChain(capability);
    }

    /** 当前注册的所有客户端 */
    public Set<String> registeredCandidateIds() {
        return snapshotRef.get().clientsById().keySet();
    }

    // ======================== Runtime Control ========================

    /** 运行时禁用某个 candidate */
    public void disable(String candidateId) {
        snapshotRef.updateAndGet(current -> {
            Set<String> newDisabled = new java.util.LinkedHashSet<>(current.disabledSet());
            if (newDisabled.add(candidateId)) {
                log.info("Disabled candidate: {}", candidateId);
            }
            return current.withDisabledSet(Set.copyOf(newDisabled));
        });
    }

    /** 运行时启用某个 candidate */
    public void enable(String candidateId) {
        snapshotRef.updateAndGet(current -> {
            Set<String> newDisabled = new java.util.LinkedHashSet<>(current.disabledSet());
            if (newDisabled.remove(candidateId)) {
                log.info("Enabled candidate: {}", candidateId);
            }
            return current.withDisabledSet(Set.copyOf(newDisabled));
        });
    }

    /** 获取当前快照（供高级查询） */
    public RegistrySnapshot snapshot() {
        return snapshotRef.get();
    }
}
