package com.smart.rag.rag.config;

import com.smart.rag.rag.retrieval.HybridSearchService;
import com.smart.rag.rag.chunk.ParentDocumentPostProcessor;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.retrieval.RerankDocumentPostProcessor;
import com.smart.rag.rag.retrieval.HybridDocumentRetriever;
import com.smart.rag.rag.retrieval.MmrDocumentPostProcessor;
import com.smart.rag.rag.retrieval.RerankThenMmrPostProcessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

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

    private final VectorStore vectorStore;
    private final VectorStoreMapper vectorStoreMapper;
    private final RagRetrievalProperties properties;
    private final HybridSearchService hybridSearchService;
    private final ParentDocumentPostProcessor parentDocumentPostProcessor;
    private final QueryTransformer rewriteQueryTransformer;

    /** Rerank 单例 Bean（null when rerank-enabled=false），生命周期由 Spring 容器管理 */
    private final RerankDocumentPostProcessor rerankPostProcessor;

    /** 后处理并行 executor（Rerank⊥distance），独立于 ragSearchExecutor */
    private final ExecutorService ragPostProcessExecutor;

    /** 缓存的 PostProcessor 列表，避免每次请求重建 */
    private volatile List<org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor> cachedPostProcessors;

    public RagAdvisorFactory(VectorStore vectorStore,
                             VectorStoreMapper vectorStoreMapper,
                             RagRetrievalProperties properties,
                             HybridSearchService hybridSearchService,
                             ParentDocumentPostProcessor parentDocumentPostProcessor,
                             QueryTransformer rewriteQueryTransformer,
                             @Nullable RerankDocumentPostProcessor rerankPostProcessor,
                             @Qualifier("ragPostProcessExecutor") ExecutorService ragPostProcessExecutor) {
        this.vectorStore = vectorStore;
        this.vectorStoreMapper = vectorStoreMapper;
        this.properties = properties;
        this.hybridSearchService = hybridSearchService;
        this.parentDocumentPostProcessor = parentDocumentPostProcessor;
        this.rewriteQueryTransformer = rewriteQueryTransformer;
        this.rerankPostProcessor = rerankPostProcessor;
        this.ragPostProcessExecutor = ragPostProcessExecutor;
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
     * 直接检索（方案 A：chat 路径不套 RetrievalAugmentationAdvisor 壳）。
     * <p>
     * 复刻 {@link #create} 的内部编排：query-transform → 隔离检索 → postProcessor（Rerank/MMR/Parent）逐个 process。
     * 100% 复用隔离 + Rerank + MMR + Parent 组件，召回行为与 create() 路径一致；返回原始 Document 列表，
     * 由 {@code ChatReferenceCollector} 统一编号 + 拼 {@code <<REF>>} 块。
     */
    public List<Document> retrieve(String query, Long userId, @Nullable Long teamId) {
        Objects.requireNonNull(userId, "userId must not be null for RAG retrieval");
        Query queryObj = new Query(query);
        if (properties.queryRewriteEnabled()) {
            queryObj = rewriteQueryTransformer.transform(queryObj);
        }
        DocumentRetriever retriever = createIsolatedRetriever(userId, teamId);
        List<Document> docs = new ArrayList<>(retriever.retrieve(queryObj));
        for (org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor pp : getPostProcessors()) {
            docs = pp.process(queryObj, docs);
        }
        log.info("Chat retrieval: {} docs for userId={}, teamId={}", docs.size(), userId, teamId);
        return docs;
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

        boolean rerankOn = rerankPostProcessor != null;
        boolean mmrOn = properties.mmrEnabled();

        if (rerankOn && mmrOn) {
            // 1. 复合处理器：Rerank(LLM 精排) ⊥ MMR distance 预取(DB) 并行 → MMR 贪心。
            //    封装进单个 process() 是 Spring AI Advisor(final) postProcessor 链硬编码顺序下的唯一并行形态（design §2）。
            MmrDocumentPostProcessor mmr = new MmrDocumentPostProcessor(
                    properties.mmrLambda(), properties.mmrTopK(), properties.fusionTopK(), vectorStoreMapper);
            postProcessors.add(new RerankThenMmrPostProcessor(rerankPostProcessor, mmr, ragPostProcessExecutor));
        } else if (rerankOn) {
            // 仅 Rerank（MMR 关闭）
            postProcessors.add(rerankPostProcessor);
        } else if (mmrOn) {
            // 仅 MMR（Rerank 关闭，MMR 用 rrfScore 作相关性 fallback）
            postProcessors.add(new MmrDocumentPostProcessor(
                    properties.mmrLambda(), properties.mmrTopK(), properties.fusionTopK(), vectorStoreMapper));
        }

        // 末步：Parent-Child 子块→父文档替换（串行，输入依赖精排+去冗余存活文档，无下游可重叠）
        postProcessors.add(parentDocumentPostProcessor);

        return postProcessors;
    }
}
