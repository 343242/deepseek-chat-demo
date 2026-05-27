package com.smart.rag.chat.advisor;

import com.smart.rag.exception.RateLimitExceededException;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 限流 Advisor
 * <p>
 * 基于 RateLimiter 接口实现按用户维度的限流。
 * 限流 key 策略：认证用户 → userId，未认证 → 固定前缀 "anon"。
 * 这样避免无 conversationId 时生成随机 UUID 绕过限流的问题。
 * 纯限流器操作，耗时 < 1ms。
 */
public class RateLimitAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAdvisor.class);

    /** 未认证用户的限流 key 前缀（所有匿名请求共享一个桶） */
    private static final String ANONYMOUS_KEY = "anon";

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
        String rateLimitKey = extractRateLimitKey();

        if (!rateLimiter.tryAcquire(rateLimitKey)) {
            log.warn("Rate limit exceeded for key: {}", rateLimitKey);
            throw new RateLimitExceededException("请求过于频繁，请稍后再试");
        }

        log.debug("Rate limit check passed for key: {}", rateLimitKey);
        return request;
    }

    @Override
    @NonNull
    public ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain chain) {
        return response;
    }

    /**
     * 提取限流 key：认证用户用 userId，未认证用户用固定 "anon" 前缀。
     * <p>
     * SecurityContextHolder 由 Spring Security FilterChain 在 servlet 线程中设置，
     * 即使在 advisor 链中也能通过 ThreadLocal 访问。
     */
    private String extractRateLimitKey() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() != null
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getPrincipal();
        }
        return ANONYMOUS_KEY;
    }
}
