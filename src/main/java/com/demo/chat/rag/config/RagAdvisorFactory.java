package com.demo.chat.rag.config;

import com.demo.chat.rag.chunk.ParentDocumentPostProcessor;
import com.demo.chat.rag.retrieval.BailianRerankPostProcessor;
import com.demo.chat.rag.retrieval.HybridDocumentRetriever;
import com.demo.chat.rag.retrieval.MmrDocumentPostProcessor;
import com.demo.chat.rag.retrieval.QueryNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG Advisor 工厂 — 按请求动态创建带用户隔离的 RetrievalAugmentationAdvisor
 * <p>
 * 核心设计：
 * <ul>
 *   <li>每次请求创建新的 Advisor 实例，携带当前用户的 userId filter</li>
 *   <li>向量检索通过 FilterExpression 按 userId 隔离</li>
 *   <li>BM25 检索通过 SQL WHERE 条件按 userId 隔离</li>
 * </ul>
 * <p>
 * 替代原先的全局单例 RagConfig.retrievalAugmentationAdvisor Bean。
 */
@Component
public class RagAdvisorFactory {

    private static final Logger log = LoggerFactory.getLogger(RagAdvisorFactory.class);

    private final ChatClient.Builder chatClientBuilder;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final RagRetrievalProperties properties;
    private final ParentDocumentPostProcessor parentDocumentPostProcessor;
    private final QueryTransformer rewriteQueryTransformer;
    private final QueryNormalizer queryNormalizer;

    /** 缓存的后处理器链（配置不变时复用） */
    private volatile List<org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor> cachedPostProcessors;

    public RagAdvisorFactory(ChatClient.Builder chatClientBuilder,
                             VectorStore vectorStore,
                             JdbcTemplate jdbcTemplate,
                             RagRetrievalProperties properties,
                             ParentDocumentPostProcessor parentDocumentPostProcessor,
                             QueryTransformer rewriteQueryTransformer,
                             QueryNormalizer queryNormalizer) {
        this.chatClientBuilder = chatClientBuilder;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.parentDocumentPostProcessor = parentDocumentPostProcessor;
        this.rewriteQueryTransformer = rewriteQueryTransformer;
        this.queryNormalizer = queryNormalizer;
    }

    /**
     * 为指定用户创建 RAG Advisor
     *
     * @param userId 当前用户 ID，用于检索隔离
     * @return 带用户过滤的 RetrievalAugmentationAdvisor
     */
    public RetrievalAugmentationAdvisor create(Long userId) {
        List<QueryTransformer> queryTransformers = new ArrayList<>();
        if (properties.isQueryRewriteEnabled()) {
            queryTransformers.add(rewriteQueryTransformer);
        }

        // 用户隔离的检索器
        DocumentRetriever retriever = createUserIsolatedRetriever(userId);

        // 后处理器链（缓存复用）
        List<org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor> postProcessors = getPostProcessors();

        // 构建 Advisor
        var builder = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .documentPostProcessors(postProcessors);

        if (!queryTransformers.isEmpty()) {
            builder.queryTransformers(queryTransformers);
        }

        log.debug("Created RAG Advisor for userId={}: rewrite={}, hybrid={}, rerank={}, mmr={}",
                userId, properties.isQueryRewriteEnabled(), properties.isHybridRetrievalEnabled(),
                properties.isRerankEnabled(), properties.isMmrEnabled());

        return builder.build();
    }

    /**
     * 创建带用户隔离的文档检索器
     */
    private DocumentRetriever createUserIsolatedRetriever(Long userId) {
        // 向量检索的 userId 过滤表达式
        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        var userIdFilter = filterBuilder.eq("userId", String.valueOf(userId)).build();

        if (properties.isHybridRetrievalEnabled()) {
            return new HybridDocumentRetriever(vectorStore, jdbcTemplate, properties, queryNormalizer, userId);
        }

        return VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(properties.getSimilarityThreshold())
                .topK(properties.getVectorTopK())
                .filterExpression(userIdFilter)
                .build();
    }

    /**
     * 获取后处理器链（首次构建后缓存）
     */
    private List<org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor> getPostProcessors() {
        if (cachedPostProcessors == null) {
            cachedPostProcessors = List.copyOf(buildPostProcessors());
        }
        return cachedPostProcessors;
    }

    /**
     * 构建后处理器链（隔离在检索器层面完成，后处理器无需关心用户隔离）
     */
    private List<org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor> buildPostProcessors() {
        List<org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor> postProcessors = new ArrayList<>();

        if (properties.isRerankEnabled() && properties.getRerankApiKey() != null) {
            postProcessors.add(new BailianRerankPostProcessor(
                    properties.getRerankBaseUrl(),
                    properties.getRerankApiKey(),
                    properties.getRerankModel(),
                    properties.getRerankTopN()
            ));
        }

        if (properties.isMmrEnabled()) {
            postProcessors.add(new MmrDocumentPostProcessor(
                    properties.getMmrLambda(),
                    properties.getMmrTopK()
            ));
        }

        postProcessors.add(parentDocumentPostProcessor);

        return postProcessors;
    }
}
