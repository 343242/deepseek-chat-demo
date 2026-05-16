package com.demo.chat.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

/**
 * Token 用量记录器
 * <p>
 * 从 ChatServiceImpl 提取，封装 UsageService 的调用细节，
 * 对外暴露简洁的 recordUsage 方法。
 */
@Component
public class ChatUsageTracker {

    private static final Logger log = LoggerFactory.getLogger(ChatUsageTracker.class);

    private final UsageService usageService;

    public ChatUsageTracker(UsageService usageService) {
        this.usageService = usageService;
    }

    /**
     * 从 AI 响应中提取并记录 Token 用量
     *
     * @param conversationId 会话 ID
     * @param modelId        模型 ID
     * @param aiResponse     AI 响应（含 usage 元数据）
     * @param durationMs     调用耗时（毫秒）
     */
    public void recordUsage(String conversationId, String modelId,
                            org.springframework.ai.chat.model.ChatResponse aiResponse, long durationMs) {
        try {
            Usage usage = aiResponse.getMetadata().getUsage();
            if (usage != null) {
                usageService.recordUsage(
                        conversationId, modelId,
                        usage.getPromptTokens(), usage.getCompletionTokens(),
                        usage.getTotalTokens(), durationMs);
                log.debug("Usage recorded: model={}, prompt={}, completion={}, total={}, duration={}ms",
                        modelId, usage.getPromptTokens(), usage.getCompletionTokens(),
                        usage.getTotalTokens(), durationMs);
            }
        } catch (Exception e) {
            log.warn("Failed to record usage: {}", e.getMessage());
        }
    }

    /**
     * 记录用量（无 AI 响应时的降级版本，使用默认值 -1）
     *
     * @param conversationId 会话 ID
     * @param modelId        模型 ID
     * @param durationMs     调用耗时（毫秒）
     */
    public void recordUsage(String conversationId, String modelId, long durationMs) {
        try {
            usageService.recordUsage(conversationId, modelId, -1, -1, -1, durationMs);
        } catch (Exception e) {
            log.warn("Failed to record usage: {}", e.getMessage());
        }
    }
}
