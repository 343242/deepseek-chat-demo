package com.demo.chat.chat.service;

import com.demo.chat.chat.advisor.ConversationContextAdvisor;
import com.demo.chat.chat.dto.ChatRequest;
import com.demo.chat.chat.mode.ChatModeStrategy;
import com.demo.chat.chat.tool.ToolRegistry;
import com.demo.chat.rag.config.RagAdvisorFactory;
import com.demo.chat.security.util.SecurityUtils;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 */
@Component
public class ChatAdvisorChainFactory {

    private final ChatMemory chatMemory;
    private final List<Advisor> globalAdvisors;
    private final ToolCallAdvisor toolCallAdvisor;
    private final ToolRegistry toolRegistry;
    private final RagAdvisorFactory ragAdvisorFactory;

    public ChatAdvisorChainFactory(ChatMemory chatMemory,
                                   List<Advisor> globalAdvisors,
                                   ToolCallAdvisor toolCallAdvisor,
                                   ToolRegistry toolRegistry,
                                   RagAdvisorFactory ragAdvisorFactory) {
        this.chatMemory = chatMemory;
        this.globalAdvisors = globalAdvisors;
        this.toolCallAdvisor = toolCallAdvisor;
        this.toolRegistry = toolRegistry;
        this.ragAdvisorFactory = ragAdvisorFactory;
    }

    /**
     * 是否有可用工具
     */
    public boolean hasTools() {
        return toolRegistry.hasTools();
    }

    /**
     * 获取工具回调数组
     */
    public ToolCallback[] getToolCallbacks() {
        return toolRegistry.getToolCallbacks();
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

        chain.addAll(globalAdvisors);

        if (request.isRagEnabled()) {
            Long userId = SecurityUtils.getCurrentUserId();
            RetrievalAugmentationAdvisor ragAdvisor = ragAdvisorFactory.create(userId);
            chain.add(ragAdvisor);
        }

        if (hasTools()) {
            chain.add(toolCallAdvisor);
        }

        if (modeStrategy.isMemoryEnabled()) {
            chain.add(MessageChatMemoryAdvisor.builder(chatMemory).build());
        }

        return chain;
    }
}
