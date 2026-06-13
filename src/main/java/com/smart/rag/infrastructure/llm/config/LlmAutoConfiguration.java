package com.smart.rag.infrastructure.llm.config;

import com.smart.rag.infrastructure.llm.EmbeddingCapable;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.client.bailian.BailianEmbeddingClient;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.infrastructure.llm.resilience.AbstractResilientClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * LLM 自动配置
 * <p>
 * 职责：
 * <ol>
 *   <li>注册 {@link BailianEmbeddingClient} 为 {@code @Primary EmbeddingModel}，
 *       供 PgVectorStore + AnswerRelevanceScorer 直接注入使用</li>
 * </ol>
 * <p>
 * LlmConfig 的配置绑定由主应用的 {@code @ConfigurationPropertiesScan("com.smart.rag")} 处理，
 * 无需在此显式注册。
 */
@Configuration
public class LlmAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmAutoConfiguration.class);

    private final MeterRegistry meterRegistry;

    public LlmAutoConfiguration(@Autowired(required = false) MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmMetrics llmMetrics() {
        return new LlmMetrics(meterRegistry);
    }

    /**
     * 将 BailianEmbeddingClient 注册为 @Primary EmbeddingModel
     * <p>
     * PgVectorStore 和 AnswerRelevanceScorer 需要 EmbeddingModel bean，
     * BailianEmbeddingClient 同时实现了 EmbeddingCapable（SPI）和 EmbeddingModel（Spring AI）。
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel primaryEmbeddingModel(LlmClientRegistry registry) {
        var client = registry.getDefault(LlmCapability.EMBEDDING);
        Object target = client instanceof AbstractResilientClient<?> arc ? arc.getDelegate() : client;
        if (target instanceof BailianEmbeddingClient bec) {
            log.info("Registered BailianEmbeddingClient as @Primary EmbeddingModel");
            return bec;
        }
        if (target instanceof EmbeddingModel em) {
            log.info("Registered {} as @Primary EmbeddingModel", target.getClass().getSimpleName());
            return em;
        }
        throw new IllegalStateException(
            "Default embedding client does not implement EmbeddingModel: " + client.getClass().getName());
    }
}
