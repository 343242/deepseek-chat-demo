package com.demo.deepseekchat.chat;

import com.demo.deepseekchat.exception.ModelNotFoundException;
import com.demo.deepseekchat.model.dto.ModelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatClient 注册中心
 * <p>
 * 管理所有已注册的 ChatClient 实例。
 * 单一职责：只负责存储和查询，不管创建。
 */
@Component
public class ChatClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatClientRegistry.class);

    private final Map<String, ChatClient> registry = new ConcurrentHashMap<>();
    private volatile List<ModelInfo> cachedModels = Collections.emptyList();

    /**
     * 注册一个 ChatClient
     */
    public void register(String modelId, ChatClient chatClient) {
        registry.put(modelId, chatClient);
        log.debug("Registered ChatClient for model: {}", modelId);
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
        return Collections.unmodifiableSet(registry.keySet());
    }

    /**
     * 清空所有注册
     */
    public void clear() {
        registry.clear();
        cachedModels = Collections.emptyList();
    }

    /**
     * 缓存模型详情列表
     */
    public void setCachedModels(List<ModelInfo> models) {
        this.cachedModels = Collections.unmodifiableList(models);
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
