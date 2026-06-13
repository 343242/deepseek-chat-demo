package com.smart.rag.infrastructure.llm.registry;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 注册表快照 — 不可变的一致性读取视图
 * <p>
 * 通过 AtomicReference 实现无锁读写分离：
 * 写操作（refresh / disable / enable）构建新快照后 CAS 替换，
 * 读操作直接读取当前快照，保证看到一致的状态。
 */
public record RegistrySnapshot(
    /** candidateId → CapabilityClient（已包装 Resilient） */
    Map<String, CapabilityClient> clientsById,

    /** capability → 按 priority 排序的客户端列表（Fallback Chain） */
    Map<LlmCapability, List<CapabilityClient>> fallbackChains,

    /** capability → 默认客户端 candidateId */
    Map<LlmCapability, String> defaultClients,

    /** capability → deep-thinking 客户端 candidateId */
    Map<LlmCapability, String> deepThinkingClients,

    /** capability → pre-filtered fallback chain (disabled candidates removed) */
    Map<LlmCapability, List<CapabilityClient>> filteredChains,

    /** 运行时禁用的 candidateId 集合 */
    Set<String> disabledSet
) {

    private static final Logger log = LoggerFactory.getLogger(RegistrySnapshot.class);

    public RegistrySnapshot {
        clientsById = Collections.unmodifiableMap(clientsById);
        fallbackChains = Collections.unmodifiableMap(fallbackChains);
        defaultClients = Collections.unmodifiableMap(defaultClients);
        deepThinkingClients = Collections.unmodifiableMap(deepThinkingClients);
        Set<String> finalDisabled = Set.copyOf(disabledSet);
        disabledSet = finalDisabled;
        Map<LlmCapability, List<CapabilityClient>> filtered = new EnumMap<>(LlmCapability.class);
        for (var entry : fallbackChains.entrySet()) {
            List<CapabilityClient> chain = entry.getValue().stream()
                .filter(c -> !finalDisabled.contains(c.candidateId()))
                .toList();
            filtered.put(entry.getKey(), chain);
        }
        filteredChains = Collections.unmodifiableMap(filtered);
    }

    public static RegistrySnapshot empty() {
        return new RegistrySnapshot(
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
    }

    /** 按 candidateId 查找客户端（忽略禁用的） */
    public CapabilityClient getClient(String candidateId) {
        if (disabledSet.contains(candidateId)) {
            log.debug("Candidate '{}' is disabled, returning null", candidateId);
            return null;
        }
        return clientsById.get(candidateId);
    }

    /** 判断指定 candidateId 是否已被运行时禁用 */
    public boolean isDisabled(String candidateId) {
        return disabledSet.contains(candidateId);
    }

    /** 获取指定能力的 Fallback Chain（已排除禁用的，预计算） */
    public List<CapabilityClient> getChain(LlmCapability capability) {
        return filteredChains.getOrDefault(capability, List.of());
    }

    /** 获取指定能力的默认客户端 */
    public CapabilityClient getDefaultClient(LlmCapability capability) {
        String candidateId = defaultClients.get(capability);
        return candidateId != null ? getClient(candidateId) : null;
    }

    /** 获取指定能力的 deep-thinking 客户端 */
    public CapabilityClient getDeepThinkingClient(LlmCapability capability) {
        String candidateId = deepThinkingClients.get(capability);
        return candidateId != null ? getClient(candidateId) : null;
    }

    /** 所有已注册的客户端数量 */
    public int size() {
        return clientsById.size();
    }
}
