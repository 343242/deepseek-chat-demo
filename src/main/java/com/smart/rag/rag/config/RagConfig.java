package com.smart.rag.rag.config;

import com.smart.rag.infrastructure.llm.ChatCapable;
import com.smart.rag.infrastructure.llm.LlmCapability;
import com.smart.rag.infrastructure.llm.RerankCapable;
import com.smart.rag.infrastructure.llm.registry.LlmClientRegistry;
import com.smart.rag.rag.chunk.ParentDocumentPostProcessor;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.retrieval.RerankDocumentPostProcessor;
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
 */
@Configuration
@EnableConfigurationProperties(RagRetrievalProperties.class)
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    // ======================== 查询改写 ========================

    @Bean
    public RewriteQueryTransformer rewriteQueryTransformer(
            ChatClient.Builder chatClientBuilder,
            RagRetrievalProperties properties,
            LlmClientRegistry llmRegistry) {

        String template = """
                Given the following user query, rewrite it into a clear and specific search query \
                suitable for querying a {target}. Keep the core intent, remove conversational filler, \
                and use precise terminology.

                IMPORTANT: If the query is already clear, specific, and standalone, return it EXACTLY as is.
                Do NOT over-elaborate short factual queries.

                Original query: {query}

                Rewritten search query:""";

        ChatClient.Builder builder = resolveRewriteBuilder(
                chatClientBuilder, properties, llmRegistry);

        return RewriteQueryTransformer.builder()
                .chatClientBuilder(builder)
                .promptTemplate(new PromptTemplate(template))
                .build();
    }

    private ChatClient.Builder resolveRewriteBuilder(
            ChatClient.Builder defaultBuilder,
            RagRetrievalProperties properties,
            LlmClientRegistry llmRegistry) {

        String rewriteCandidateId = properties.queryRewriteModel();
        if (rewriteCandidateId == null || rewriteCandidateId.isBlank()) {
            log.info("Query rewrite using default ChatClient");
            return defaultBuilder;
        }

        ChatCapable chatCapable = llmRegistry.get(rewriteCandidateId, ChatCapable.class);
        if (chatCapable == null) {
            log.warn("Query rewrite candidate '{}' not found, falling back to default", rewriteCandidateId);
            return defaultBuilder;
        }

        ChatClient rewriteClient = ChatClient.builder(chatCapable.asChatModel()).build();
        log.info("Query rewrite using candidate '{}'", rewriteCandidateId);

        return rewriteClient.mutate();
    }

    // ======================== 后处理器 ========================

    @Bean
    public ParentDocumentPostProcessor parentDocumentPostProcessor(VectorStoreMapper vectorStoreMapper) {
        log.info("ParentDocumentPostProcessor registered");
        return new ParentDocumentPostProcessor(vectorStoreMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.rerank-enabled", havingValue = "true")
    public RerankDocumentPostProcessor rerankDocumentPostProcessor(
            LlmClientRegistry llmClientRegistry,
            RagRetrievalProperties properties) {
        RerankCapable reranker = llmClientRegistry.getDefault(LlmCapability.RERANKING, RerankCapable.class);
        log.info("RerankDocumentPostProcessor registered: candidate={}", reranker.candidateId());
        return new RerankDocumentPostProcessor(reranker, 10);
    }
}
