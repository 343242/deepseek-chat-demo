package com.smart.rag.config;

import com.smart.rag.infrastructure.advisor.ContentFilterAdvisor;
import com.smart.rag.infrastructure.advisor.FallbackRateLimiter;
import com.smart.rag.infrastructure.advisor.RateLimitAdvisor;
import com.smart.rag.infrastructure.advisor.RateLimiter;
import com.smart.rag.infrastructure.advisor.TokenBucketLimiter;
import com.smart.rag.infrastructure.content.ContentFilterService;
import com.smart.rag.infrastructure.content.SensitiveWordFilterService;

import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

/**
 * Advisor 编排配置
 * <p>
 * 集中管理所有 Advisor 的创建和注册。
 * ChatService 通过注入 List&lt;BaseAdvisor&gt; 自动获取所有已注册的 Advisor，
 * 无需关心具体类型和数量。
 * <p>
 * 同时管理 ChatMemory Bean 和令牌桶定时清理。
 */
@Configuration
@EnableScheduling
public class AdvisorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AdvisorAutoConfiguration.class);

    @Lazy
    private final TokenBucketLimiter tokenBucketLimiter;

    private final ObjectProvider<FallbackRateLimiter> fallbackRateLimiterProvider;

    public AdvisorAutoConfiguration(
            @Lazy TokenBucketLimiter tokenBucketLimiter,
            ObjectProvider<FallbackRateLimiter> fallbackRateLimiterProvider) {
        this.tokenBucketLimiter = tokenBucketLimiter;
        this.fallbackRateLimiterProvider = fallbackRateLimiterProvider;
    }

    // ==================== 限流 ====================

    @Bean
    public TokenBucketLimiter tokenBucketLimiter() {
        return new TokenBucketLimiter(10, 2.0, Duration.ofHours(1));
    }

    @Bean
    @Primary
    @ConditionalOnBean(RedissonClient.class)
    public FallbackRateLimiter fallbackRateLimiter(RedissonClient redissonClient) {
        return new FallbackRateLimiter(redissonClient, tokenBucketLimiter(), 2.0);
    }

    @Bean
    public RateLimitAdvisor rateLimitAdvisor(RateLimiter rateLimiter) {
        return new RateLimitAdvisor(rateLimiter);
    }

    /**
     * 每 10 分钟清理一次空闲令牌桶，防止内存无限增长。
     * 同时尝试从 Redis 降级模式恢复。
     */
    @Scheduled(fixedRate = 600000)
    public void cleanupIdleBuckets() {
        int removed = tokenBucketLimiter.cleanIdleBuckets();
        if (removed > 0) {
            log.info("Cleaned up {} idle token buckets, remaining: {}", removed, tokenBucketLimiter.bucketCount());
        }
        // Attempt Redis recovery if in fallback mode
        FallbackRateLimiter limiter = fallbackRateLimiterProvider.getIfAvailable();
        if (limiter != null) {
            limiter.attemptRecovery();
        }
    }

    // ==================== 内容安全 ====================

    @Bean
    public ContentFilterService contentFilterService() {
        return new SensitiveWordFilterService();
    }

    @Bean
    public ContentFilterAdvisor contentFilterAdvisor(ContentFilterService contentFilterService) {
        return new ContentFilterAdvisor(contentFilterService);
    }

    // ==================== 对话记忆 ====================

    /**
     * ChatMemoryRepository Bean 由 RedisChatMemoryAutoConfiguration 提供（Redis + Lettuce）。
     * <p>
     * 原先使用 JdbcChatMemoryRepository + PostgreSQL，已切换为 Redis 实现。
     */

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository,
                                   @Value("${app.chat.memory.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build();
    }

    // ==================== 对话模式路由 ====================
    // SimpleModeStrategy / MultiTurnModeStrategy / AgentModeStrategy 已改为 @Component 自动注册
    // ModeRouter 已改为 @Component，构造时 fail-fast 校验全部 ChatMode
}
