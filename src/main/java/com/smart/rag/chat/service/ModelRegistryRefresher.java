package com.smart.rag.chat.service;

import com.smart.rag.chat.client.ChatClientRegistry;
import com.smart.rag.chat.dto.ModelInfo;
import com.smart.rag.chat.provider.ModelProvider;
import com.smart.rag.chat.provider.ProviderRegistry;
import com.smart.rag.common.concurrent.ScopeOptions;
import com.smart.rag.common.concurrent.ScopePolicy;
import com.smart.rag.common.concurrent.ScopedTasks;
import com.smart.rag.common.concurrent.Subtask;
import com.smart.rag.common.concurrent.TaskScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 模型注册刷新器（多 Provider 版）
 * <p>
 * 遍历所有可用的 ModelProvider，聚合所有厂商的模型到 ChatClientRegistry。
 * 单个 Provider 拉取失败不影响其他 Provider（try-catch 隔离）。
 * <p>
 * 被 CommandLineRunner（启动时）和 ModelService（手动刷新）共同复用。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>OCP — 新增 Provider 后自动被遍历，不需要修改此类</li>
 *   <li>容错 — 单个 Provider 失败不阻塞其他 Provider 的模型注册</li>
 * </ul>
 */
@Component
public class ModelRegistryRefresher {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryRefresher.class);

    private final ProviderRegistry providerRegistry;
    private final ChatClientRegistry chatClientRegistry;
    private final ScopedTasks scopedTasks;

    /** modelId → providerId 反向索引（刷新时构建，O(1) 查询） */
    private volatile Map<String, String> modelToProvider = Map.of();

    public ModelRegistryRefresher(ProviderRegistry providerRegistry,
                                  ChatClientRegistry chatClientRegistry,
                                  ScopedTasks scopedTasks) {
        this.providerRegistry = providerRegistry;
        this.chatClientRegistry = chatClientRegistry;
        this.scopedTasks = scopedTasks;
    }

    /**
     * 从所有可用 Provider 拉取模型，创建 ChatClient，原子替换 Registry。
     * <p>
     * 策略：先在临时 Map/List 中构建所有模型，全部完成后一次性替换。
     * 不会出现 "清空后某个 Provider 失败导致无模型可用" 的中间状态。
     *
     * @return true 至少有一个 Provider 刷新成功
     */
    public boolean refresh() {
        List<ModelProvider> providers = new ArrayList<>(providerRegistry.getAll());
        log.info("Refreshing models from {} providers: {}",
                providers.size(), providerRegistry.getAvailableProviderIds());

        String scopeName = "model-registry-refresh";
        List<ProviderResult> results;
        ScopeOptions options = ScopeOptions.builder(scopeName)
                .policy(ScopePolicy.COLLECT_ALL)
                .build();
        try (TaskScope scope = scopedTasks.open(scopeName, options)) {
            List<Subtask<ProviderResult>> subtasks = providers.stream()
                    .map(provider -> scope.fork("fetch-" + provider.getProviderId(), () -> fetchModels(provider)))
                    .toList();

            scope.join();
            rethrowFatalFailures(subtasks);
            results = subtasks.stream()
                    .map(Subtask::result)
                    .toList();
        }

        // ====== 阶段2: 串行创建 ChatClient 并注册（CPU 密集，需要原子性） ======
        Map<String, ChatClient> newClients = new LinkedHashMap<>();
        Map<String, ChatModel> newChatModels = new LinkedHashMap<>();
        List<ModelInfo> allModels = new ArrayList<>();
        Map<String, String> newIndex = new HashMap<>();
        int successCount = 0;

        for (ProviderResult result : results) {
            if (result.error() != null) {
                continue;
            }
            if (result.models().isEmpty()) {
                log.warn("Provider {} returned empty model list", result.provider().getProviderId());
                continue;
            }

            for (ModelInfo model : result.models()) {
                try {
                    ModelProvider.ClientAndModel cam = result.provider().createClientWithModel(model.id(), null);
                    ChatClient client = cam.client();
                    ChatModel chatModel = cam.chatModel();
                    String compositeKey = result.provider().getProviderId() + "/" + model.id();
                    newClients.put(compositeKey, client);
                    if (chatModel != null) {
                        newChatModels.put(compositeKey, chatModel);
                    }
                    if (newClients.containsKey(model.id())) {
                        log.warn("Model '{}' already registered by provider '{}', skipping registration from '{}' (use composite key '{}' instead)",
                                model.id(),
                                newIndex.get(model.id()),
                                result.provider().getProviderId(),
                                compositeKey);
                    } else {
                        newClients.put(model.id(), client);
                        if (chatModel != null) {
                            newChatModels.put(model.id(), chatModel);
                        }
                    }
                    allModels.add(model);
                    newIndex.putIfAbsent(model.id(), result.provider().getProviderId());
                    newIndex.putIfAbsent(compositeKey, result.provider().getProviderId());
                } catch (Exception e) {
                    log.warn("Failed to create client for {}/{}: {}",
                            result.provider().getProviderId(), model.id(), e.getMessage());
                }
            }
            successCount++;
            log.info("Provider {}: registered {} models", result.provider().getProviderId(), result.models().size());
        }

        boolean hasClients = !newClients.isEmpty();
        if (hasClients) {
            // 原子更新：先更新 provider 索引（降级可接受），再更新 registry
            // modelToProvider 是查询辅助索引，中间态影响有限（查询返回 null）
            // ChatClientRegistry.replaceAll 使用 3-arg 版本同时写入 ChatClient + ChatModel
            modelToProvider = Collections.unmodifiableMap(newIndex);
            chatClientRegistry.replaceAll(newClients, newChatModels, allModels);
        }

        log.info("Refresh complete: {} clients, {} models from {}/{} providers",
                newClients.size(), allModels.size(), successCount, providers.size());

        return successCount > 0;
    }

    private void rethrowFatalFailures(List<Subtask<ProviderResult>> subtasks) {
        for (Subtask<ProviderResult> subtask : subtasks) {
            if (subtask.exception() instanceof Error error) {
                throw error;
            }
        }
    }

    /**
     * Provider 拉取结果载体
     */
    private ProviderResult fetchModels(ModelProvider provider) {
        try {
            List<ModelInfo> models = provider.fetchModels();
            return new ProviderResult(provider, models, null);
        } catch (Error error) {
            throw error;
        } catch (Throwable t) {
            log.error("Failed to fetch models from {}: {}", provider.getProviderId(), t.getMessage());
            return new ProviderResult(provider, List.of(), t);
        }
    }

    private record ProviderResult(ModelProvider provider, List<ModelInfo> models, Throwable error) {}

    /**
     * 查找模型所属的 Provider（O(1)，基于刷新时构建的索引）
     *
     * @param modelId 纯模型 ID（如 "deepseek-chat"）
     * @return providerId，未找到时返回 null
     */
    public String getProviderIdForModel(String modelId) {
        return modelToProvider.get(modelId);
    }
}
