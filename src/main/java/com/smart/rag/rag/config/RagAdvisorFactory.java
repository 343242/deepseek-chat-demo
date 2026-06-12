package com.smart.rag.rag.config;

import com.smart.rag.agent.service.HybridSearchService;
import com.smart.rag.rag.chunk.ParentDocumentPostProcessor;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.retrieval.RerankDocumentPostProcessor;
import com.smart.rag.rag.retrieval.HybridDocumentRetriever;
import com.smart.rag.rag.retrieval.MmrDocumentPostProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
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
import java.util.Objects;

/**
 * RAG Advisor 工厂 -- 按请求动态创建带用户/团队隔离的 RetrievalAugmentationAdvisor
 * <p>
 * 核心设计：
 * <ul>
 *   <li>每次请求创建新的 Advisor 实例（Advisor 是轻量对象，无需缓存）</li>
 *   <li>个人检索：按 userId 过滤向量数据</li>
 *   <li>团队检索：按 teamId 过滤向量数据</li>
 * </ul>
 */
@Component
public class RagAdvisorFactory {

    private static final Logger log = LoggerFactory.getLogger(RagAdvisorFactory.class);

    private final ChatClient.Builder chatClientBuilder;
    private final VectorStore vectorStore;
    private final VectorStoreMapper vectorStoreMapper;
    private final JdbcTemplate jdbcTemplate;
    private final RagRetrievalProperties properties;
    private final HybridSearchService hybridSearchService;
    private final ParentDocumentPostProcessor parentDocumentPostProcessor;
    private final QueryTransformer rewriteQueryTransformer;
    private final ObjectMapper objectMapper;

    /** Rerank 单例 Bean（null when rerank-enabled=false），生命周期由 Spring 容器管理 */
    private final RerankDocumentPostProcessor rerankPostProcessor;

    /** 缓存的 PostProcessor 列表，避免每次请求重建 */
    private volatile List<org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor> cachedPostProcessors;

    public RagAdvisorFactory(ChatClient.Builder chatClientBuilder,
                             VectorStore vectorStore,
                             VectorStoreMapper vectorStoreMapper,
                             JdbcTemplate jdbcTemplate,
                             RagRetrievalProperties properties,
                             HybridSearchService hybridSearchService,
                             ParentDocumentPostProcessor parentDocumentPostProcessor,
                             QueryTransformer rewriteQueryTransformer,
                             ObjectMapper objectMapper,
                             @Nullable RerankDocumentPostProcessor rerankPostProcessor) {
        this.chatClientBuilder = chatClientBuilder;
        this.vectorStore = vectorStore;
        this.vectorStoreMapper = vectorStoreMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.hybridSearchService = hybridSearchService;
        this.parentDocumentPostProcessor = parentDocumentPostProcessor;
        this.rewriteQueryTransformer = rewriteQueryTransformer;
        this.objectMapper = objectMapper;
        this.rerankPostProcessor = rerankPostProcessor;
    }

    /**
     * 为指定用户/团队创建 RAG Advisor
     *
     * @param userId 当前用户 ID
     * @param teamId 团队 ID（null=个人检索）
     * @return 带隔离过滤的 RetrievalAugmentationAdvisor
     */
    public RetrievalAugmentationAdvisor create(Long userId, @Nullable Long teamId) {
        Objects.requireNonNull(userId, "userId must not be null for RAG retrieval");

        List<QueryTransformer> queryTransformers = new ArrayList<>();
        if (properties.queryRewriteEnabled()) {
            queryTransformers.add(rewriteQueryTransformer);
        }

        DocumentRetriever retriever = createIsolatedRetriever(userId, teamId);
        List<org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor> postProcessors = getPostProcessors();

        var builder = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .documentPostProcessors(postProcessors);

        if (!queryTransformers.isEmpty()) {
            builder.queryTransformers(queryTransformers);
        }

        log.debug("Created RAG Advisor for userId={}, teamId={}", userId, teamId);
        return builder.build();
    }

    /**
     * 创建带隔离的文档检索器
     * <p>
     * teamId != null: 按 teamId 过滤（团队知识库）
     * teamId == null: 按 userId 过滤（个人知识库）
     */
    private DocumentRetriever createIsolatedRetriever(Long userId, @Nullable Long teamId) {
        if (teamId != null) {
            // 团队检索：按 teamId 隔离
            if (properties.hybridRetrievalEnabled()) {
                return new HybridDocumentRetriever(hybridSearchService, userId, teamId);
            }
            FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
            var teamIdFilter = filterBuilder.eq("teamId", String.valueOf(teamId)).build();
            return VectorStoreDocumentRetriever.builder()
                    .vectorStore(vectorStore)
                    .similarityThreshold(properties.similarityThreshold())
                    .topK(properties.vectorTopK())
                    .filterExpression(teamIdFilter)
                    .build();
        }

        // 个人检索：按 userId 隔离
        if (properties.hybridRetrievalEnabled()) {
            return new HybridDocumentRetriever(hybridSearchService, userId, null);
        }
        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        var userIdFilter = filterBuilder.eq("userId", String.valueOf(userId)).build();
        return VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(properties.similarityThreshold())
                .topK(properties.vectorTopK())
                .filterExpression(userIdFilter)
                .build();
    }

    private List<org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor> getPostProcessors() {
        if (cachedPostProcessors != null) {
            return cachedPostProcessors;
        }
        synchronized (this) {
            if (cachedPostProcessors != null) {
                return cachedPostProcessors;
            }
            cachedPostProcessors = List.copyOf(buildPostProcessors());
            return cachedPostProcessors;
        }
    }

    private List<org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor> buildPostProcessors() {
        List<org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor> postProcessors = new ArrayList<>();

        // 1. MMR 多样性去冗余（粗召回 → 去重，减少后续 Rerank 算力浪费）
        if (properties.mmrEnabled()) {
            postProcessors.add(new MmrDocumentPostProcessor(
                    properties.mmrLambda(),
                    properties.mmrTopK(),
                    vectorStoreMapper
            ));
        }

        // 2. Rerank 语义精排（去冗余后 → 精排，聚焦有效候选）
        //    使用注入的单例 Bean，生命周期由 Spring 容器管理
        if (rerankPostProcessor != null) {
            postProcessors.add(rerankPostProcessor);
        }

        // 3. Parent-Child 子块→父文档替换
        postProcessors.add(parentDocumentPostProcessor);

        return postProcessors;
    }
}
