package com.demo.deepseekchat.service;

import com.demo.deepseekchat.chat.ChatClientRegistry;
import com.demo.deepseekchat.model.dto.ChatRequest;
import com.demo.deepseekchat.model.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天服务
 * <p>
 * 通过 ChatClientRegistry 获取模型对应的 ChatClient，
 * 通过 Spring 自动注入的 List<BaseAdvisor> 获取所有 Advisor，
 * 不直接依赖任何具体 Advisor 实现。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClientRegistry registry;
    private final ChatMemory chatMemory;
    private final List<Advisor> advisors;

    public ChatService(ChatClientRegistry registry, List<Advisor> advisors) {
        this.registry = registry;
        this.advisors = advisors;
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }

    /**
     * 阻塞式聊天
     */
    public ChatResponse chat(ChatRequest request) {
        log.debug("Chat request: model={}, conversationId={}", request.model(), request.conversationId());

        ChatClient chatClient = registry.get(request.model());

        String content = chatClient.prompt()
                .user(request.message())
                .advisors(buildAdvisors(request.conversationId()))
                .call()
                .content();

        return new ChatResponse(request.model(), content, request.conversationId());
    }

    /**
     * 流式聊天，返回 SSE 事件流
     */
    public Flux<String> chatStream(ChatRequest request) {
        log.debug("Stream chat request: model={}, conversationId={}", request.model(), request.conversationId());

        ChatClient chatClient = registry.get(request.model());

        return chatClient.prompt()
                .user(request.message())
                .advisors(buildAdvisors(request.conversationId()))
                .stream()
                .content();
    }

    /**
     * 构建 Advisor 列表：自定义 Advisors + Memory Advisor
     * <p>
     * 自定义 Advisors 通过 Spring List<BaseAdvisor> 自动注入，按 order 排序。
     * Memory Advisor 按 conversationId 动态创建，放在最后。
     */
    private List<Advisor> buildAdvisors(String conversationId) {
        List<Advisor> allAdvisors = new ArrayList<>(advisors);
        allAdvisors.add(MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(conversationId)
                .build());
        return allAdvisors;
    }
}
