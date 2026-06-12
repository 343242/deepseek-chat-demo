package com.smart.rag.infrastructure.llm.registry;

import com.smart.rag.infrastructure.llm.CapabilityClient;
import com.smart.rag.infrastructure.llm.LlmCapability;

import java.util.Collections;
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

    /** 运行时禁用的 candidateId 集合 */
    Set<String> disabledSet
) {

    public static RegistrySnapshot empty() {
        return new RegistrySnapshot(
            Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
    }

    /** 按 candidateId 查找客户端（忽略禁用的） */
    public CapabilityClient getClient(String candidateId) {
        if (disabledSet.contains(candidateId)) return null;
        return clientsById.get(candidateId);
    }

    /** 获取指定能力的 Fallback Chain（排除禁用的） */
    public List<CapabilityClient> getChain(LlmCapability capability) {
        List<CapabilityClient> chain = fallbackChains.getOrDefault(capability, List.of());
        if (disabledSet.isEmpty()) return chain;
        return chain.stream()
            .filter(c -> !disabledSet.contains(c.candidateId()))
            .toList();
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
