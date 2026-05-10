package com.demo.chat.chat.client;

import com.demo.chat.exception.ModelNotFoundException;
import com.demo.chat.chat.dto.ModelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ChatClient 注册中心
 * <p>
 * 管理所有已注册的 ChatClient 实例。
 * 单一职责：只负责存储和查询，不管创建。
 * <p>
 * 线程安全策略：
 * <ul>
 *   <li>{@code registry} 使用 volatile 引用 + 不可变 Map，读操作无锁、线程安全</li>
 *   <li>{@code cachedModels} 同样是 volatile + 不可变 List</li>
 *   <li>写操作 ({@link #register}, {@link #replaceAll}) 使用 synchronized 保证原子性</li>
 * </ul>
 * <p>
 * 并发语义说明：{@link #replaceAll} 构建新 Map/List 期间，{@link #get} 可能读到旧的
 * registry 引用。这是设计预期内的最终一致性——旧 Map 不可变，读操作始终安全。
 * 在 {@code replaceAll} 完成后，后续 {@code get} 调用会立即看到新 Map（volatile 保证可见性）。
 * <p>
 * 注意：{@code cachedModels} 和 {@code registry} 在 {@code replaceAll} 中一起更新，
 * 但两者是独立的 volatile 字段，中间态下可能短暂不一致（新 registry + 旧 cachedModels
 * 或反之）。调用方如果需要强一致性，应自行同步。
 */
@Component
public class ChatClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatClientRegistry.class);

    private volatile Map<String, ChatClient> registry = Collections.emptyMap();
    private volatile List<ModelInfo> cachedModels = Collections.emptyList();

    /**
     * 注册一个 ChatClient（单个追加，线程安全）
     */
    public synchronized void register(String modelId, ChatClient chatClient) {
        Map<String, ChatClient> newMap = new LinkedHashMap<>(registry);
        newMap.put(modelId, chatClient);
        this.registry = Collections.unmodifiableMap(newMap);
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
        this.registry = Collections.unmodifiableMap(new LinkedHashMap<>(newClients));
        this.cachedModels = List.copyOf(newModels);
        log.info("Registry replaced: {} models", registry.size());
    }

    /**
     * 获取指定模型的 ChatClient，不存在则抛异常
     */
    public ChatClient get(String modelId) {
        ChatClient client = registry.get(modelId);
        if (client == null) {
            throw new ModelNotFoundException(modelId,
                    "Model not found: " + modelId + ". Available: " + registry.keySet());
        }
        return client;
    }

    /**
     * 获取所有已注册的模型 ID
     */
    public Set<String> getAvailableModelIds() {
        return registry.keySet();
    }

    /**
     * 缓存模型详情列表（defensive copy）
     */
    public void setCachedModels(List<ModelInfo> models) {
        this.cachedModels = List.copyOf(models);
    }

    /**
     * 获取缓存的模型详情列表
     */
    public List<ModelInfo> getCachedModels() {
        return cachedModels;
    }

    /**
     * 检查模型是否已注册
     */
    public boolean contains(String modelId) {
        return registry.containsKey(modelId);
    }

    /**
     * 获取已注册数量
     */
    public int size() {
        return registry.size();
    }
}
