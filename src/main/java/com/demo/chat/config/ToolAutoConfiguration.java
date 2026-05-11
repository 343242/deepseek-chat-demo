package com.demo.chat.config;

import com.demo.chat.chat.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;

/**
 * Tool Calling 自动配置
 * <p>
 * 注册 Spring AI 的 {@link ToolCallingManager} 和 {@link ToolCallAdvisor}。
 * ToolCallAdvisor 在 advisor 链中处理模型的工具调用请求，
 * 位于限流（order=0）和内容安全（order=1）之后、对话记忆之前。
 * <p>
 * 设置 {@code disableMemory()} 因为已有 {@link org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor}
 * 管理对话历史，避免重复。
 */
@Configuration
public class ToolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ToolAutoConfiguration.class);

    /**
     * 工具调用管理器 — 负责分发工具调用到对应的 ToolCallback
     * <p>
     * 从 ToolRegistry 获取所有已注册的 ToolCallback，通过 StaticToolCallbackResolver 注册。
     */
    @Lazy
    @Bean
    public ToolCallingManager toolCallingManager(ToolRegistry toolRegistry) {
        ToolCallback[] callbacks = toolRegistry.getToolCallbacks();

        DefaultToolCallingManager manager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(List.of(callbacks)))
                .build();

        log.info("ToolCallingManager initialized with {} tool callbacks", callbacks.length);
        return manager;
    }

    /**
     * 工具调用 Advisor — 在 advisor 链中处理工具调用循环
     * <p>
     * order=2：位于 RateLimit(0) 和 ContentFilter(1) 之后。
     * 禁用内部对话历史管理（disableMemory），由 MessageChatMemoryAdvisor 统一管理。
     */
    @Lazy
    @Bean
    public ToolCallAdvisor toolCallAdvisor(ToolCallingManager toolCallingManager) {
        return ToolCallAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .disableMemory()
                .advisorOrder(2)
                .build();
    }
}
