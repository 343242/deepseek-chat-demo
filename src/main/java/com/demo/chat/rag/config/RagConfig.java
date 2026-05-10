package com.demo.chat.rag.config;

import com.demo.chat.rag.chunk.ParentDocumentPostProcessor;
import com.demo.chat.rag.retrieval.BailianRerankPostProcessor;
import com.demo.chat.rag.retrieval.HybridDocumentRetriever;
import com.demo.chat.rag.retrieval.MmrDocumentPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 配置
 * <p>
 * 完整检索 Pipeline：
 * <pre>
 * 用户查询
 *   ↓ RewriteQueryTransformer（查询改写）
 *   ↓ HybridDocumentRetriever（pgvector + BM25, RRF 融合）
 *   ↓ BailianRerankPostProcessor（百炼 Rerank 语义精排）
 *   ↓ MmrDocumentPostProcessor（MMR 多样性去重）
 *   ↓ ParentDocumentPostProcessor（子块→父文档替换）
 *   ↓ 注入 LLM prompt
 * </pre>
 * </p>
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    // ======================== 查询改写 ========================

    @Bean
    public RewriteQueryTransformer rewriteQueryTransformer(ChatClient.Builder chatClientBuilder) {
        String template = """
                Given the following user query, rewrite it into a clear and specific search query \
                suitable for document retrieval. Keep the core intent, remove conversational filler, \
                and use precise terminology.
                
                Original query: {query}
                
                Rewritten search query:""";

        return RewriteQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .promptTemplate(new PromptTemplate(template))
                .build();
    }

    // ======================== 混合检索 ========================

    @Bean
    public DocumentRetriever documentRetriever(VectorStore vectorStore,
                                               JdbcTemplate jdbcTemplate,
                                               RagRetrievalProperties properties) {
        if (properties.isHybridRetrievalEnabled()) {
            log.info("Hybrid retrieval enabled (vector + BM25, RRF k={})", properties.getRrfK());
            return new HybridDocumentRetriever(vectorStore, jdbcTemplate, properties);
        }

        log.info("Vector-only retrieval mode");
        return VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(properties.getSimilarityThreshold())
                .topK(properties.getVectorTopK())
                .build();
    }

    // ======================== 后处理器 ========================

    @Bean
    public ParentDocumentPostProcessor parentDocumentPostProcessor(VectorStore vectorStore) {
        log.info("ParentDocumentPostProcessor registered");
        return new ParentDocumentPostProcessor(vectorStore);
    }

    // ======================== RAG Advisor 集成 ========================

    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            RewriteQueryTransformer rewriteQueryTransformer,
            ParentDocumentPostProcessor parentDocumentPostProcessor,
            RagRetrievalProperties properties) {

        // 查询改写
        List<org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer> queryTransformers = new ArrayList<>();
        if (properties.isQueryRewriteEnabled()) {
            queryTransformers.add(rewriteQueryTransformer);
            log.info("Query rewrite enabled");
        }

        // 检索器
        DocumentRetriever retriever;
        if (properties.isHybridRetrievalEnabled()) {
            retriever = new HybridDocumentRetriever(vectorStore, jdbcTemplate, properties);
            log.info("Hybrid retrieval (vector + BM25 + RRF)");
        } else {
            retriever = VectorStoreDocumentRetriever.builder()
                    .vectorStore(vectorStore)
                    .similarityThreshold(properties.getSimilarityThreshold())
                    .topK(properties.getVectorTopK())
                    .build();
            log.info("Vector-only retrieval");
        }

        // 后处理器链
        List<org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor> postProcessors = new ArrayList<>();

        if (properties.isRerankEnabled() && properties.getRerankApiKey() != null) {
            postProcessors.add(new BailianRerankPostProcessor(
                    properties.getRerankBaseUrl(),
                    properties.getRerankApiKey(),
                    properties.getRerankModel(),
                    properties.getRerankTopN()
            ));
            log.info("Rerank enabled: model={}, topN={}", properties.getRerankModel(), properties.getRerankTopN());
        } else {
            log.info("Rerank disabled (no API key or disabled)");
        }

        if (properties.isMmrEnabled()) {
            postProcessors.add(new MmrDocumentPostProcessor(
                    properties.getMmrLambda(),
                    properties.getMmrTopK()
            ));
            log.info("MMR enabled: lambda={}, topK={}", properties.getMmrLambda(), properties.getMmrTopK());
        }

        postProcessors.add(parentDocumentPostProcessor);

        // 构建 Advisor
        var builder = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .documentPostProcessors(postProcessors);

        if (!queryTransformers.isEmpty()) {
            builder.queryTransformers(queryTransformers);
        }

        log.info("RetrievalAugmentationAdvisor: rewrite={}, hybrid={}, rerank={}, mmr={}, parentDoc=true",
                properties.isQueryRewriteEnabled(),
                properties.isHybridRetrievalEnabled(),
                properties.isRerankEnabled(),
                properties.isMmrEnabled());

        return builder.build();
    }
}
