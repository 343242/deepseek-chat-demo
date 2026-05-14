package com.demo.chat.chat.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Auto-configuration for Redis-backed {@link ChatMemoryRepository}.
 * <p>
 * Uses Lettuce (via Spring Data Redis) + Jackson for serialization.
 * Replaces the previous JDBC-based implementation.
 */
@Configuration
public class RedisChatMemoryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ChatMemoryRepository.class)
    public ChatMemoryRepository chatMemoryRepository(StringRedisTemplate redisTemplate,
                                                      ObjectMapper objectMapper) {
        return RedisChatMemoryRepository.builder()
                .redisTemplate(redisTemplate)
                .objectMapper(objectMapper)
                .build();
    }
}
