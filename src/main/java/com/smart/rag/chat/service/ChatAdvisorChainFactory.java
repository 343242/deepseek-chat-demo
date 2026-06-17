package com.smart.rag.chat.service;

import com.smart.rag.chat.advisor.ConversationContextAdvisor;
import com.smart.rag.chat.dto.ChatRequest;
import com.smart.rag.chat.mode.ChatModeStrategy;
import com.smart.rag.chat.tool.ToolRegistry;
import com.smart.rag.rag.config.RagAdvisorFactory;
import com.smart.rag.security.util.SecurityUtils;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Advisor 链构建工厂
 * <p>
 * 根据对话模式和请求参数，组装不同的 Advisor 链。
 * 单一职责：只负责 Advisor 链的构建逻辑，不关心 ChatClient 和请求参数。
 * <p>
 * Advisor 链顺序：
 * <ul>
 *   <li>SIMPLE: RateLimit → ContentFilter → [RAG] → [ToolCall]</li>
 *   <li>MULTI_TURN: ConversationContext → RateLimit → ContentFilter → [RAG] → [ToolCall] → Memory</li>
 * </ul>
 * <p>
 * 使用 {@link ObjectProvider} 延迟解析 ToolCallAdvisor / ToolRegistry / globalAdvisors，
 * 打断构造器注入循环链。
 */
@Component
public class ChatAdvisorChainFactory {

    private final ChatMemory chatMemory;
    private final ObjectProvider<List<Advisor>> globalAdvisorsProvider;
    private final ObjectProvider<ToolCallAdvisor> toolCallAdvisorProvider;
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    private final RagAdvisorFactory ragAdvisorFactory;

    /** 缓存的全局 Advisor 列表（不可变，初始化后不再变化） */
    private volatile List<Advisor> cachedGlobalAdvisors;
    /** 缓存的工具可用状态 */
    private volatile Boolean cachedHasTools;
    /** 缓存的工具回调数组（不可变引用） */
    private volatile ToolCallback[] cachedToolCallbacks;

    public ChatAdvisorChainFactory(ChatMemory chatMemory,
                                   ObjectProvider<List<Advisor>> globalAdvisors,
                                   ObjectProvider<ToolCallAdvisor> toolCallAdvisor,
                                   ObjectProvider<ToolRegistry> toolRegistry,
                                   RagAdvisorFactory ragAdvisorFactory) {
        this.chatMemory = chatMemory;
        this.globalAdvisorsProvider = globalAdvisors;
        this.toolCallAdvisorProvider = toolCallAdvisor;
        this.toolRegistryProvider = toolRegistry;
        this.ragAdvisorFactory = ragAdvisorFactory;
    }

    /**
     * 是否有可用工具（首次调用后缓存）
     */
    public boolean hasTools() {
        if (cachedHasTools == null) {
            synchronized (this) {
                if (cachedHasTools == null) {
                    cachedHasTools = toolRegistryProvider.getIfAvailable(ToolRegistry::empty).hasTools();
                }
            }
        }
        return cachedHasTools;
    }

    /**
     * 获取工具回调数组（首次调用后缓存）
     */
    public ToolCallback[] getToolCallbacks() {
        if (cachedToolCallbacks == null) {
            synchronized (this) {
                if (cachedToolCallbacks == null) {
                    cachedToolCallbacks = toolRegistryProvider.getIfAvailable(ToolRegistry::empty).getToolCallbacks();
                }
            }
        }
        return cachedToolCallbacks;
    }

    /**
     * 获取缓存的全局 Advisor 列表（首次调用后缓存）
     */
    private List<Advisor> getGlobalAdvisors() {
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

    /**
     * 构建完整的 Advisor 链
     *
     * @param conversationId 隔离后的对话 ID
     * @param request        聊天请求
     * @param modeStrategy   对话模式策略
     * @return 有序 Advisor 列表
     */
    public List<Advisor> buildChain(String conversationId,
                                    ChatRequest request,
                                    ChatModeStrategy modeStrategy) {
        List<Advisor> chain = new ArrayList<>();

        if (modeStrategy.isContextEnabled()) {
            chain.add(new ConversationContextAdvisor(conversationId));
        }

        List<Advisor> globals = getGlobalAdvisors();
        chain.addAll(globals);

        if (request.isRagEnabled()) {
            Long userId = SecurityUtils.getCurrentUserId();
            Long teamId = request.teamId();
            RetrievalAugmentationAdvisor ragAdvisor = ragAdvisorFactory.create(userId, teamId);
            chain.add(ragAdvisor);
        }

        if (hasTools()) {
            chain.add(toolCallAdvisorProvider.getObject());
        }

        if (modeStrategy.isMemoryEnabled()) {
            chain.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
        }

        return chain;
    }
}
