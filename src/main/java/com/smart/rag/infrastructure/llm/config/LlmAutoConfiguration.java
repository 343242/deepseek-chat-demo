package com.smart.rag.infrastructure.llm.config;

import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.client.bailian.BailianEmbeddingClient;
import com.smart.rag.infrastructure.llm.client.bailian.BailianSpringAiEmbeddingAdapter;
import com.smart.rag.infrastructure.llm.metrics.LlmMetrics;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.infrastructure.llm.resilience.AbstractResilientClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

/**
 * LLM 自动配置
 * <p>
 * 职责：
 * <ol>
 *   <li>将默认 embedding 客户端注册为容器内唯一的 {@code EmbeddingModel} bean，
 *       供 PgVectorStore + AnswerRelevanceScorer 直接注入使用。</li>
 * </ol>
 * <p>
 * <b>EmbeddingModel 适配策略</b>：SPI 客户端（如 {@link BailianEmbeddingClient}）只实现
 * {@code EmbeddingCapable}，不直接实现 Spring AI {@link EmbeddingModel}。本配置负责在
 * SPI 客户端与 Spring AI 之间架桥：
 * <ul>
 *   <li>{@link BailianEmbeddingClient} → {@link BailianSpringAiEmbeddingAdapter} 包装为 EmbeddingModel</li>
 *   <li>其他已实现 {@code EmbeddingModel} 的客户端 → 直接返回</li>
 * </ul>
 * <p>
 * LlmConfig 的配置绑定由主应用的 {@code @ConfigurationPropertiesScan("com.smart.rag")} 处理，
 * 无需在此显式注册。
 */
@Configuration
public class LlmAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmAutoConfiguration.class);

    private final MeterRegistry meterRegistry;

    public LlmAutoConfiguration(@Nullable MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmMetrics llmMetrics() {
        return new LlmMetrics(meterRegistry);
    }

    /**
     * 将默认 embedding 客户端注册为 {@code EmbeddingModel}。
     * <p>
     * PgVectorStore 和 AnswerRelevanceScorer 需要 EmbeddingModel bean。
     * SPI 客户端只实现 EmbeddingCapable，需通过适配器桥接到 Spring AI。
     * <ul>
     *   <li>{@link BailianEmbeddingClient} → 包装为 {@link BailianSpringAiEmbeddingAdapter}</li>
     *   <li>其他 EmbeddingModel 实现 → 直接返回</li>
     * </ul>
     * <p>
     * {@code @ConditionalOnMissingBean} 保证容器内至多一个 EmbeddingModel：
     * 若外部已注册其他实现则本 bean 退避，否则本 bean 是唯一候选，按类型注入无需 {@code @Primary}。
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel embeddingModel(LlmClientRegistry registry) {
        var client = registry.getDefault(LlmCapability.EMBEDDING);
        Object target = client instanceof AbstractResilientClient<?> arc ? arc.getDelegate() : client;
        if (target instanceof BailianSpringAiEmbeddingAdapter adapter) {
            log.info("Registered BailianSpringAiEmbeddingAdapter as EmbeddingModel");
            return adapter;
        }
        if (target instanceof BailianEmbeddingClient bec) {
            log.info("Wrapping BailianEmbeddingClient with BailianSpringAiEmbeddingAdapter as EmbeddingModel");
            return new BailianSpringAiEmbeddingAdapter(bec);
        }
        if (target instanceof EmbeddingModel em) {
            log.info("Registered {} as EmbeddingModel", target.getClass().getSimpleName());
            return em;
        }
        throw new IllegalStateException(
            "Default embedding client cannot be adapted to EmbeddingModel: " + client.getClass().getName());
    }
}
