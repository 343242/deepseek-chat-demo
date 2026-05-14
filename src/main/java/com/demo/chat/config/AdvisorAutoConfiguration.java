package com.demo.chat.config;

import com.demo.chat.chat.advisor.ContentFilterAdvisor;
import com.demo.chat.chat.advisor.RateLimitAdvisor;
import com.demo.chat.chat.advisor.RateLimiter;
import com.demo.chat.chat.advisor.TokenBucketLimiter;
import com.demo.chat.chat.content.ContentFilterService;
import com.demo.chat.chat.content.SensitiveWordFilterService;
import com.demo.chat.chat.mode.ModeRouter;
import com.demo.chat.chat.mode.ChatModeStrategy;
import com.demo.chat.chat.mode.SimpleModeStrategy;
import com.demo.chat.chat.mode.MultiTurnModeStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    // ==================== 限流 ====================

    @Bean
    public TokenBucketLimiter tokenBucketLimiter() {
        return new TokenBucketLimiter(10, 2.0, Duration.ofHours(1));
    }

    @Bean
    public RateLimitAdvisor rateLimitAdvisor(RateLimiter rateLimiter) {
        return new RateLimitAdvisor(rateLimiter);
    }

    @Lazy
    private final TokenBucketLimiter tokenBucketLimiter;

    public AdvisorAutoConfiguration(@Lazy TokenBucketLimiter tokenBucketLimiter) {
        this.tokenBucketLimiter = tokenBucketLimiter;
    }

    /**
     * 每小时清理一次空闲令牌桶，防止内存无限增长
     */
    @Scheduled(fixedRate = 3600000)
    public void cleanupIdleBuckets() {
        int removed = tokenBucketLimiter.cleanIdleBuckets();
        if (removed > 0) {
            org.slf4j.LoggerFactory.getLogger(AdvisorAutoConfiguration.class)
                    .info("Cleaned up {} idle token buckets, remaining: {}", removed, tokenBucketLimiter.bucketCount());
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
