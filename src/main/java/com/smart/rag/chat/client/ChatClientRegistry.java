package com.smart.rag.chat.client;

import com.smart.rag.exception.ModelNotFoundException;
import com.smart.rag.chat.dto.ModelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ChatClient 注册中心
 * <p>
 * 管理所有已注册的 ChatClient 实例及其对应的 ChatModel。
 * 单一职责：只负责存储和查询，不管创建。
 * <p>
 * 线程安全策略：
 * <ul>
 *   <li>所有可变状态封装在单一的 {@link Snapshot} 对象中</li>
 *   <li>{@code snapshot} 使用 volatile 引用，读操作无锁、线程安全</li>
 *   <li>每次写操作构造全新的不可变 Snapshot 并原子替换 volatile 引用</li>
 * </ul>
 * <p>
 * 并发语义说明：{@link #replaceAll} 构建新 Snapshot 期间，{@link #get} 可能读到旧的
 * Snapshot。这是设计预期内的最终一致性——旧 Snapshot 不可变，读操作始终安全。
 * 在 {@code replaceAll} 完成后，后续 {@code get} 调用会立即看到新数据（volatile 保证可见性）。
 * <p>
 * 原子性保证：因为 registry、modelRegistry、cachedModels 三个字段封装在同一个
 * Snapshot 对象中，读操作永远不会看到 "新 registry + 旧 modelRegistry" 的中间态。
 */
@Component
public class ChatClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatClientRegistry.class);

    /**
     * 不可变快照 — 将所有可读状态封装在单一对象中。
     * 读操作通过 volatile snapshot 引用一次性获取全部状态，保证强一致性视图。
     */
    private static final class Snapshot {
        final Map<String, ChatClient> registry;
        final Map<String, ChatModel> modelRegistry;
        final List<ModelInfo> cachedModels;

        Snapshot() {
            this.registry = Collections.emptyMap();
            this.modelRegistry = Collections.emptyMap();
            this.cachedModels = Collections.emptyList();
        }

        Snapshot(Map<String, ChatClient> registry,
                 Map<String, ChatModel> modelRegistry,
                 List<ModelInfo> cachedModels) {
            this.registry = Collections.unmodifiableMap(new LinkedHashMap<>(registry));
            this.modelRegistry = Collections.unmodifiableMap(new LinkedHashMap<>(modelRegistry));
            this.cachedModels = List.copyOf(cachedModels);
        }
    }

    /** 单一 volatile 引用，读操作无锁，写操作构造新 Snapshot 后原子替换 */
    private volatile Snapshot snapshot = new Snapshot();

    /**
     * 注册一个 ChatClient 及其对应的 ChatModel（单个追加，线程安全）
     */
    public synchronized void register(String modelId, ChatClient chatClient, ChatModel chatModel) {
        Snapshot current = snapshot;
        Map<String, ChatClient> newClients = new LinkedHashMap<>(current.registry);
        newClients.put(modelId, chatClient);

        Map<String, ChatModel> newModels = new LinkedHashMap<>(current.modelRegistry);
        newModels.put(modelId, chatModel);

        snapshot = new Snapshot(newClients, newModels, current.cachedModels);
        log.debug("Registered ChatClient for model: {}", modelId);
    }

    /**
     * 注册一个 ChatClient（不含 ChatModel 引用，向后兼容）
     */
    public synchronized void register(String modelId, ChatClient chatClient) {
        Snapshot current = snapshot;
        Map<String, ChatClient> newClients = new LinkedHashMap<>(current.registry);
        newClients.put(modelId, chatClient);

        snapshot = new Snapshot(newClients, current.modelRegistry, current.cachedModels);
        log.debug("Registered ChatClient for model: {}", modelId);
    }

    /**
     * 原子替换所有注册（用于刷新场景）
     * <p>
     * 不会出现清空后重建失败的中间状态。
     *
     * @param newClients 新的模型→ChatClient 映射
     * @param newModels  新的模型信息列表
     */
    public synchronized void replaceAll(Map<String, ChatClient> newClients, List<ModelInfo> newModels) {
        snapshot = new Snapshot(newClients, Collections.emptyMap(), newModels);
        log.info("Registry replaced: {} models", snapshot.registry.size());
    }

    /**
     * 原子替换所有注册（含 ChatModel 引用）
     */
    public synchronized void replaceAll(Map<String, ChatClient> newClients,
                                        Map<String, ChatModel> newChatModels,
                                        List<ModelInfo> newModels) {
        snapshot = new Snapshot(newClients, newChatModels, newModels);
        log.info("Registry replaced: {} models", snapshot.registry.size());
    }

    /**
     * 获取指定模型的 ChatClient，不存在则抛异常
     */
    public ChatClient get(String modelId) {
        ChatClient client = snapshot.registry.get(modelId);
        if (client == null) {
            log.warn("Model not found: {}, available: {}", modelId, snapshot.registry.keySet());
            throw new ModelNotFoundException(modelId,
                    "Model not found: " + modelId);
        }
        return client;
    }

    /**
     * 获取指定模型的 ChatModel（用于 Token 计数等需要底层模型的场景）
     */
    public ChatModel getChatModel(String modelId) {
        return snapshot.modelRegistry.get(modelId);
    }

    /**
     * 获取所有已注册的模型 ID
     */
    public Set<String> getAvailableModelIds() {
        return snapshot.registry.keySet();
    }

    /**
     * 缓存模型详情列表（defensive copy）
     */
    public void setCachedModels(List<ModelInfo> models) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(current.registry, current.modelRegistry, models);
    }

    /**
     * 获取缓存的模型详情列表
     */
    public List<ModelInfo> getCachedModels() {
        return snapshot.cachedModels;
    }

    /**
     * 检查模型是否已注册
     */
    public boolean contains(String modelId) {
        return snapshot.registry.containsKey(modelId);
    }

    /**
     * 获取已注册数量
     */
    public int size() {
        return snapshot.registry.size();
    }
}
