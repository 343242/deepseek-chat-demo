package com.smart.rag.chat.advisor;

import com.smart.rag.exception.RateLimitExceededException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.Map;
import java.util.UUID;

/**
 * 限流 Advisor
 * <p>
 * 基于 RateLimiter 接口实现按 conversationId 的限流。
 * 纯内存操作，耗时 < 1ms。
 */
public class RateLimitAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAdvisor.class);

    private final RateLimiter rateLimiter;

    /**
     * @param rateLimiter 限流器接口，解耦具体算法
     */
    public RateLimitAdvisor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    @NonNull
    public String getName() {
        return "RateLimitAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    @NonNull
    public ChatClientRequest before(@NonNull ChatClientRequest request, @NonNull AdvisorChain chain) {
        String conversationId = extractConversationId(request);

        if (!rateLimiter.tryAcquire(conversationId)) {
            log.warn("Rate limit exceeded for conversation: {}", conversationId);
            throw new RateLimitExceededException("请求过于频繁，请稍后再试");
        }

        log.debug("Rate limit check passed for conversation: {}", conversationId);
        return request;
    }

    @Override
    @NonNull
    public ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain chain) {
        return response;
    }

    private String extractConversationId(ChatClientRequest request) {
        Map<String, Object> context = request.context();
        Object convId = context.get(ChatMemory.CONVERSATION_ID);
        if (convId != null && !convId.toString().isBlank()) {
            return convId.toString();
        }
        // 无 conversationId 时用随机 key，避免不同请求共享桶导致互相限流
        log.debug("No conversationId in context, using isolated rate-limit bucket");
        return UUID.randomUUID().toString();
    }
}
