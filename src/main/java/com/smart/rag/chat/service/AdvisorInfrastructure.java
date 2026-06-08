package com.smart.rag.chat.service;

import com.smart.rag.chat.tool.ToolRegistry;
import com.smart.rag.rag.config.RagAdvisorFactory;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Advisor 链构建的门面服务 -- 共享基础设施
 * <p>
 * 从 ChatAdvisorChainFactory 提取共享基础设施，提供带缓存的统一访问入口。
 * 保留原有 ObjectProvider + volatile DCL 延迟初始化语义。
 */
@Component
public class AdvisorInfrastructure {

    // 保留原有 ObjectProvider 类型 -- 与当前 ChatAdvisorChainFactory 一致
    private final ObjectProvider<List<Advisor>> globalAdvisorsProvider;
    private final ObjectProvider<ChatMemory> chatMemoryProvider;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    private final ObjectProvider<ToolCallAdvisor> toolCallAdvisorProvider;
    private final RagAdvisorFactory ragAdvisorFactory;

    // volatile + DCL 缓存 -- 保留原有延迟初始化语义
    private volatile List<Advisor> cachedGlobalAdvisors;
    private volatile ToolCallback[] cachedToolCallbacks;
    private volatile Boolean cachedHasTools;

    public AdvisorInfrastructure(ObjectProvider<List<Advisor>> globalAdvisorsProvider,
                                  ObjectProvider<ChatMemory> chatMemoryProvider,
                                  ChatMemoryRepository chatMemoryRepository,
                                  ObjectProvider<ToolRegistry> toolRegistryProvider,
                                  ObjectProvider<ToolCallAdvisor> toolCallAdvisorProvider,
                                  RagAdvisorFactory ragAdvisorFactory) {
        this.globalAdvisorsProvider = globalAdvisorsProvider;
        this.chatMemoryProvider = chatMemoryProvider;
        this.chatMemoryRepository = chatMemoryRepository;
        this.toolRegistryProvider = toolRegistryProvider;
        this.toolCallAdvisorProvider = toolCallAdvisorProvider;
        this.ragAdvisorFactory = ragAdvisorFactory;
    }

    public List<Advisor> getGlobalAdvisors() {
        if (cachedGlobalAdvisors == null) {
            synchronized (this) {
                if (cachedGlobalAdvisors == null) {
                    cachedGlobalAdvisors = List.copyOf(
                        globalAdvisorsProvider.getIfAvailable(Collections::emptyList));
                }
            }
        }
        return cachedGlobalAdvisors;
    }

    public boolean hasTools() {
        if (cachedHasTools == null) {
            synchronized (this) {
                if (cachedHasTools == null) {
                    ToolRegistry registry = toolRegistryProvider.getIfAvailable(ToolRegistry::empty);
                    cachedHasTools = registry.hasTools();
                    cachedToolCallbacks = registry.getToolCallbacks();
                }
            }
        }
        return cachedHasTools;
    }

    public ToolCallback[] getToolCallbacks() {
        hasTools(); // 触发缓存初始化
        return cachedToolCallbacks;
    }

    public ChatMemory getChatMemory() { return chatMemoryProvider.getObject(); }
    public ChatMemoryRepository getChatMemoryRepository() { return chatMemoryRepository; }
    public RagAdvisorFactory getRagAdvisorFactory() { return ragAdvisorFactory; }
    public ToolCallAdvisor getToolCallAdvisor() { return toolCallAdvisorProvider.getObject(); }
}
