package com.smart.rag.infrastructure.llm.registry;

import com.smart.rag.infrastructure.exception.RemoteException;
import com.smart.rag.infrastructure.exception.errorcode.RemoteErrorCode;
import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    private final LlmClientFactory factory;
    private final AtomicReference<RegistrySnapshot> snapshotRef;

    public LlmClientRegistry(LlmClientFactory factory) {
        this.factory = factory;
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
        snapshot.clientsById().values().forEach(client -> {
            try { client.close(); } catch (Exception e) {
                log.warn("Failed to close client {}: {}", client.candidateId(), e.getMessage());
            }
        });
        log.info("LlmClientRegistry destroyed");
    }

    /** 重新构建快照（配置变更时调用） */
    public void refresh() {
        // Preserve runtime disabledSet across refresh
        Set<String> preservedDisabled = snapshotRef.get().disabledSet();
        RegistrySnapshot fresh = factory.buildSnapshot();
        RegistrySnapshot newSnapshot = new RegistrySnapshot(
            fresh.clientsById(), fresh.fallbackChains(),
            fresh.defaultClients(), fresh.deepThinkingClients(),
            fresh.filteredChains(), preservedDisabled);
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
            return new RegistrySnapshot(
                current.clientsById(), current.fallbackChains(),
                current.defaultClients(), current.deepThinkingClients(),
                Map.of(),
                Set.copyOf(newDisabled));
        });
    }

    /** 运行时启用某个 candidate */
    public void enable(String candidateId) {
        snapshotRef.updateAndGet(current -> {
            Set<String> newDisabled = new java.util.LinkedHashSet<>(current.disabledSet());
            if (newDisabled.remove(candidateId)) {
                log.info("Enabled candidate: {}", candidateId);
            }
            return new RegistrySnapshot(
                current.clientsById(), current.fallbackChains(),
                current.defaultClients(), current.deepThinkingClients(),
                Map.of(),
                Set.copyOf(newDisabled));
        });
    }

    /** 获取当前快照（供高级查询） */
    public RegistrySnapshot snapshot() {
        return snapshotRef.get();
    }
}
