package com.demo.deepseekchat.config;

import com.demo.deepseekchat.advisor.*;
import com.demo.deepseekchat.advisor.ContentFilterAdvisor;
import com.demo.deepseekchat.content.ContentFilterService;
import com.demo.deepseekchat.content.SensitiveWordFilterService;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Advisor 编排配置
 * <p>
 * 集中管理所有 Advisor 的创建和注册。
 * ChatService 通过注入 List<BaseAdvisor> 自动获取所有已注册的 Advisor，
 * 无需关心具体类型和数量。
 */
@Configuration
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

    // ==================== 内容安全 ====================

    @Bean
    public ContentFilterService contentFilterService() {
        return new SensitiveWordFilterService();
    }

    @Bean
    public ContentFilterAdvisor contentFilterAdvisor(ContentFilterService contentFilterService) {
        return new ContentFilterAdvisor(contentFilterService);
    }
}
