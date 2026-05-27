package com.smart.rag.config;

import com.smart.rag.chat.advisor.ContentFilterAdvisor;
import com.smart.rag.chat.advisor.FallbackRateLimiter;
import com.smart.rag.chat.advisor.RateLimitAdvisor;
import com.smart.rag.chat.advisor.RateLimiter;
import com.smart.rag.chat.advisor.TokenBucketLimiter;
import com.smart.rag.chat.content.ContentFilterService;
import com.smart.rag.chat.content.SensitiveWordFilterService;
import com.smart.rag.chat.mode.ModeRouter;
import com.smart.rag.chat.mode.ChatModeStrategy;
import com.smart.rag.chat.mode.SimpleModeStrategy;
import com.smart.rag.chat.mode.MultiTurnModeStrategy;
import org.jspecify.annotations.Nullable;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;
import java.util.List;

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

    @Lazy
    private final TokenBucketLimiter tokenBucketLimiter;

    @Nullable
    @Lazy
    private final FallbackRateLimiter fallbackRateLimiter;

    public AdvisorAutoConfiguration(
            @Lazy TokenBucketLimiter tokenBucketLimiter,
            @Nullable @Lazy FallbackRateLimiter fallbackRateLimiter) {
        this.tokenBucketLimiter = tokenBucketLimiter;
        this.fallbackRateLimiter = fallbackRateLimiter;
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
     * 每小时清理一次空闲令牌桶，防止内存无限增长。
     * 同时尝试从 Redis 降级模式恢复。
     */
    @Scheduled(fixedRate = 3600000)
    public void cleanupIdleBuckets() {
        int removed = tokenBucketLimiter.cleanIdleBuckets();
        if (removed > 0) {
            org.slf4j.LoggerFactory.getLogger(AdvisorAutoConfiguration.class)
                    .info("Cleaned up {} idle token buckets, remaining: {}", removed, tokenBucketLimiter.bucketCount());
        }
        // Attempt Redis recovery if in fallback mode
        if (fallbackRateLimiter != null) {
            fallbackRateLimiter.attemptRecovery();
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

    @Bean
    public ChatModeStrategy simpleModeStrategy() {
        return new SimpleModeStrategy();
    }

    @Bean
    public ChatModeStrategy multiTurnModeStrategy() {
        return new MultiTurnModeStrategy();
    }

    @Bean
    public ModeRouter modeRouter(List<ChatModeStrategy> strategies) {
        return new ModeRouter(strategies);
    }
}
