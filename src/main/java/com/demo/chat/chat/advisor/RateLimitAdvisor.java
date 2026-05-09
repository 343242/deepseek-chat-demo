package com.demo.chat.chat.advisor;

import com.demo.chat.exception.RateLimitExceededException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.Map;

/**
 * 限流 Advisor
 * <p>
 * 基于 RateLimiter 接口实现按 conversationId 的限流。
 * 纯内存操作，耗时 < 1ms。
 */
public class RateLimitAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAdvisor.class);
    private static final String DEFAULT_CONVERSATION_ID = "default";

    private final RateLimiter rateLimiter;

    /**
     * @param rateLimiter 限流器接口，解耦具体算法
     */
    public RateLimitAdvisor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public String getName() {
        return "RateLimitAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientRequest before(@NonNull ChatClientRequest request, @NonNull AdvisorChain chain) {
        String conversationId = extractConversationId(request);

        if (!rateLimiter.tryAcquire(conversationId)) {
            log.warn("Rate limit exceeded for conversation: {}", conversationId);
            throw new RateLimitExceededException(
                    "请求过于频繁，请稍后再试。当前对话: " + conversationId);
        }

        log.debug("Rate limit check passed for conversation: {}", conversationId);
        return request;
    }

    @Override
    public ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain chain) {
        return response;
    }

    private String extractConversationId(ChatClientRequest request) {
        Map<String, Object> context = request.context();
        Object convId = context.get(ChatMemory.CONVERSATION_ID);
        if (convId != null && !convId.toString().isBlank()) {
            return convId.toString();
        }
        return DEFAULT_CONVERSATION_ID;
    }
}
