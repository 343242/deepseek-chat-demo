package com.smart.rag.rag.config;

import com.smart.rag.rag.chunk.ParentDocumentPostProcessor;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.retrieval.BailianRerankPostProcessor;
import com.smart.rag.infrastructure.ai.provider.ModelProvider;
import com.smart.rag.infrastructure.ai.provider.ProviderRegistry;
import com.smart.rag.chat.service.ModelRegistryRefresher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 配置
 * <p>
 * 完整检索 Pipeline：
 * <pre>
 * 用户查询
 *   ↓ RewriteQueryTransformer（查询改写）
 *   ↓ HybridDocumentRetriever（pgvector + BM25, RRF 融合, userId 隔离）
 *   ↓ MmrDocumentPostProcessor（MMR 多样性去冗余）
 *   ↓ BailianRerankPostProcessor（百炼 Rerank 语义精排）
 *   ↓ ParentDocumentPostProcessor（子块→父文档替换）
 *   ↓ 注入 LLM prompt
 * </pre>
 * <p>
 * 注意：RetrievalAugmentationAdvisor 和 DocumentRetriever 不再以全局单例 Bean 存在，
 * 改为 {@link RagAdvisorFactory} 按请求动态创建（需携带当前 userId 进行检索隔离）。
 * </p>
 */
@Configuration
@EnableConfigurationProperties(RagRetrievalProperties.class)
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    // ======================== 查询改写 ========================

    /**
     * 查询改写 Transformer
     * <p>
     * 支持通过 app.rag.query-rewrite-model 和 app.rag.query-rewrite-temperature
     * 指定独立的改写模型和 temperature，复用项目已有的 ModelProvider 路由体系。
     * <p>
     * 未配置时使用全局默认 ChatClient.Builder（行为与改动前一致）。
     *
     * @param chatClientBuilder 全局默认 ChatClient.Builder（Spring AI 自动注入）
     * @param properties        RAG 配置
     * @param providerRegistry  Provider 注册中心
     * @param refresher         模型注册刷新器（用于 modelId→providerId 解析）
     */
    @Bean
    public RewriteQueryTransformer rewriteQueryTransformer(
            ChatClient.Builder chatClientBuilder,
            RagRetrievalProperties properties,
            ProviderRegistry providerRegistry,
            ModelRegistryRefresher refresher) {

        String template = """
                Given the following user query, rewrite it into a clear and specific search query \
                suitable for querying a {target}. Keep the core intent, remove conversational filler, \
                and use precise terminology.
                
                IMPORTANT: If the query is already clear, specific, and standalone, return it EXACTLY as is.
                Do NOT over-elaborate short factual queries.
                
                Original query: {query}
                
                Rewritten search query:""";

        ChatClient.Builder builder = resolveRewriteBuilder(
                chatClientBuilder, properties, providerRegistry, refresher);

        return RewriteQueryTransformer.builder()
                .chatClientBuilder(builder)
                .promptTemplate(new PromptTemplate(template))
                .build();
    }

    /**
     * 解析查询改写用的 ChatClient.Builder
     * <p>
     * 配置了 queryRewriteModel 时，通过 ProviderRegistry 路由到对应 Provider，
     * 调用 createClient(modelId, temperature) 创建独立 ChatClient，再 mutate() 为 Builder。
     * 未配置时返回全局默认 Builder。
     */
    private ChatClient.Builder resolveRewriteBuilder(
            ChatClient.Builder defaultBuilder,
            RagRetrievalProperties properties,
            ProviderRegistry providerRegistry,
            ModelRegistryRefresher refresher) {

        String rewriteModel = properties.queryRewriteModel();
        if (rewriteModel == null || rewriteModel.isBlank()) {
            log.info("Query rewrite using default ChatClient");
            return defaultBuilder;
        }

        // 解析 modelId → providerId
        String providerId = refresher.getProviderIdForModel(rewriteModel);
        if (providerId == null) {
            log.warn("Query rewrite model '{}' not found in provider index, falling back to default", rewriteModel);
            return defaultBuilder;
        }

        ModelProvider provider = providerRegistry.get(providerId);
        if (provider == null) {
            log.warn("Query rewrite provider '{}' not found in registry, falling back to default", providerId);
            return defaultBuilder;
        }

        // 提取纯 modelId（复合格式 "provider/model" → "model"）
        String pureModelId = rewriteModel.contains("/")
                ? rewriteModel.substring(rewriteModel.indexOf('/') + 1)
                : rewriteModel;

        ChatClient rewriteClient = provider.createClient(pureModelId, properties.queryRewriteTemperature());
        log.info("Query rewrite using model '{}' via provider '{}', temperature={}",
                rewriteModel, providerId, properties.queryRewriteTemperature());

        return rewriteClient.mutate();
    }

    // ======================== 后处理器 ========================

    @Bean
    public ParentDocumentPostProcessor parentDocumentPostProcessor(VectorStoreMapper vectorStoreMapper) {
        log.info("ParentDocumentPostProcessor registered");
        return new ParentDocumentPostProcessor(vectorStoreMapper);
    }

    /**
     * 百炼 Rerank 精排处理器（Spring 单例 Bean）
     * <p>
     * 仅在 rerank 启用且 API Key 已配置时创建。Spring 容器负责生命周期管理：
     * Bean 销毁时自动调用 {@link BailianRerankPostProcessor#destroy()} 关闭内部线程池。
     * <p>
     * 代替原先在 RerankTool / RagAdvisorFactory / EvaluationRunner 中 ad-hoc 创建实例的方式，
     * 统一为一个共享 Bean，避免重复创建/销毁 WebClient 和线程池。
     */
    @Bean
    @ConditionalOnProperty(name = "app.rag.rerank-enabled", havingValue = "true")
    public BailianRerankPostProcessor bailianRerankPostProcessor(RagRetrievalProperties properties) {
        log.info("BailianRerankPostProcessor bean registered: model={}, topN={}",
                properties.rerankModel(), properties.rerankTopN());
        return new BailianRerankPostProcessor(
                properties.rerankBaseUrl(),
                properties.rerankApiKey(),
                properties.rerankModel(),
                properties.rerankTopN()
        );
    }

    // ======================== RAG Advisor 集成 ========================
    // 已移至 RagAdvisorFactory — 按请求动态创建带用户隔离的 Advisor
    // 原全局单例 Bean 无法按请求携带 userId filter，存在数据泄露风险
    // 参见：RagAdvisorFactory.create(userId)

    // ======================== 混合检索 ========================
    // 全局 DocumentRetriever Bean 已移除 — 改为 RagAdvisorFactory 按请求动态创建
    // 原因：检索器需要携带当前请求的 userId，无法使用全局单例
    // 参见：RagAdvisorFactory.createUserIsolatedRetriever(userId)
}
