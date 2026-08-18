package com.smart.rag.evaluation.runner;

import com.smart.rag.rag.retrieval.HybridSearchService;
import com.smart.rag.rag.retrieval.RetrievalPath;
import com.smart.rag.rag.retrieval.VectorRetrievalPath;
import com.smart.rag.rag.retrieval.Bm25RetrievalPath;
import com.smart.rag.rag.chunk.ParentDocumentPostProcessor;
import com.smart.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.evaluation.dataset.DatasetRepository;
import com.smart.rag.evaluation.dataset.EvaluationDatasetItem;
import com.smart.rag.evaluation.metrics.generation.ContextTextBuilder;
import com.smart.rag.evaluation.metrics.generation.GenerationMetrics;
import com.smart.rag.evaluation.metrics.generation.GenerationPrompts;
import com.smart.rag.evaluation.metrics.generation.GenerationMetricsCalculator;
import com.smart.rag.evaluation.metrics.retrieval.RetrievalMetrics;
import com.smart.rag.evaluation.metrics.retrieval.RetrievalMetricsCalculator;
import com.smart.rag.evaluation.result.EvaluationResult;
import com.smart.rag.rag.config.RagRetrievalProperties;
import com.smart.rag.rag.retrieval.RerankDocumentPostProcessor;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.retrieval.HybridDocumentRetriever;
import com.smart.rag.rag.retrieval.MmrDocumentPostProcessor;
import com.smart.rag.rag.retrieval.QueryNormalizer;
import com.smart.rag.infrastructure.concurrent.ScopedTasks;
import com.smart.rag.infrastructure.llm.adapter.RewriteClientResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 评估执行引擎（核心）
 * <p>
 * 直接调用 Pipeline 各组件，在每个阶段之间插入插桩点。
 * 零侵入策略：不修改任何现有 RAG 代码，只调用公共 API。
 * </p>
 */
@Component
public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    private final VectorStore vectorStore;
    private final VectorStoreMapper vectorStoreMapper;
    private final JdbcTemplate jdbcTemplate;
    private final RagRetrievalProperties properties;
    private final ParentDocumentPostProcessor parentProcessor;
    private final QueryNormalizer queryNormalizer;
    private final QueryTransformer queryTransformer;
    private final RewriteClientResolver rewriteClientResolver;
    private final EvaluationProperties evalProps;
    private final RetrievalMetricsCalculator metricsCalculator;
    private final GenerationMetricsCalculator generationMetricsCalculator;
    private final ObjectMapper objectMapper;
    private final DatasetRepository datasetRepo;
    private final ScopedTasks scopedTasks;

    /** Rerank 单例 Bean（null when rerank-enabled=false），生命周期由 Spring 容器管理 */
    private final RerankDocumentPostProcessor rerankPostProcessor;

    public EvaluationRunner(VectorStore vectorStore,
                            VectorStoreMapper vectorStoreMapper,
                            JdbcTemplate jdbcTemplate,
                            RagRetrievalProperties properties,
                            ParentDocumentPostProcessor parentProcessor,
                            QueryNormalizer queryNormalizer,
                            QueryTransformer queryTransformer,
                            RewriteClientResolver rewriteClientResolver,
                            EvaluationProperties evalProps,
                            RetrievalMetricsCalculator metricsCalculator,
                            GenerationMetricsCalculator generationMetricsCalculator,
                            ObjectMapper objectMapper,
                            DatasetRepository datasetRepo,
                            ScopedTasks scopedTasks,
                            @Nullable RerankDocumentPostProcessor rerankPostProcessor) {
        this.vectorStore = vectorStore;
        this.vectorStoreMapper = vectorStoreMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.parentProcessor = parentProcessor;
        this.queryNormalizer = queryNormalizer;
        this.queryTransformer = queryTransformer;
        this.rewriteClientResolver = rewriteClientResolver;
        this.evalProps = evalProps;
        this.metricsCalculator = metricsCalculator;
        this.generationMetricsCalculator = generationMetricsCalculator;
        this.objectMapper = objectMapper;
        this.datasetRepo = datasetRepo;
        this.scopedTasks = scopedTasks;
        this.rerankPostProcessor = rerankPostProcessor;
    }

    /**
     * 执行单条评估
     */
    public EvaluationResult evaluate(EvaluationDatasetItem item, EvalConfig config) {
        long start = System.currentTimeMillis();

        // 收集变量，最后构建 record
        String queryRewritten = null;
        List<String> retrievedDocIds = List.of();
        RetrievalMetrics retrievalMetrics = null;
        String generatedAnswer = null;
        String generationMetrics = null;
        String error = null;

        PipelineInstrumenter inst = new PipelineInstrumenter(objectMapper);

        try {
            // 1-2. 查询规范化 + 改写（可选）
            var prepared = prepareQuery(item.question(), config, inst);
            queryRewritten = prepared.rewritten();

            // 3-6. 检索 → MMR → Rerank → ParentChild 流水线
            Query query = new Query(prepared.query());
            List<Document> finalDocs = runRetrievalPipeline(query, config, inst);

            // 7. 检索结果
            retrievedDocIds = extractedDocIds(finalDocs);

            // 8. 检索指标（基于最终列表，与存入结果行的 retrievedDocIds 同源，
            //    确保 Recall/Precision/NDCG 反映生成器实际看到的文档）
            retrievalMetrics = computeRetrievalMetrics(item, config, finalDocs);

            // 9. LLM 生成 + 生成指标
            if (config.isGenerationEnabled()) {
                generatedAnswer = generateAnswer(prepared.query(), finalDocs);
                inst.capture("after_generation", generatedAnswer);

                GenerationMetrics genMetrics = generationMetricsCalculator.calculate(
                        item.question(), generatedAnswer,
                        item.groundTruthAnswer(), finalDocs);
                generationMetrics = objectMapper.writeValueAsString(genMetrics);
            }

        } catch (Exception e) {
            log.error("Evaluation failed for item {}: {}", item.id(), e.getMessage(), e);
            error = e.getMessage();
        }

        return new EvaluationResult(
                null, 0L, item.id() != null ? item.id() : 0L,
                item.question(), item.groundTruthAnswer(), item.relevantChunkIds(),
                queryRewritten, retrievedDocIds, generatedAnswer,
                inst.getSnapshots(), retrievalMetrics, generationMetrics,
                error, (int) (System.currentTimeMillis() - start));
    }

    /** 规范化（+ 可选改写）后的查询。rewritten 仅在改写开启且成功时非 null。 */
    private record PreparedQuery(String query, @Nullable String rewritten) {
    }

    private PreparedQuery prepareQuery(String question, EvalConfig config,
                                       PipelineInstrumenter inst) {
        String normalized = queryNormalizer.normalize(question);
        inst.capture("after_normalize", normalized);
        if (!config.isQueryRewriteEnabled()) {
            inst.capture("after_rewrite", normalized);
            return new PreparedQuery(normalized, null);
        }
        String rewritten = rewriteQuery(normalized);
        inst.capture("after_rewrite", rewritten);
        return new PreparedQuery(rewritten, rewritten);
    }

    /** 检索 → MMR（可选，先去冗余减少 Rerank 算力）→ Rerank（可选）→ ParentChild（可选）。 */
    private List<Document> runRetrievalPipeline(Query query, EvalConfig config,
                                                PipelineInstrumenter inst) {
        HybridDocumentRetriever retriever = createEvalRetriever(config);
        List<Document> retrieved = retriever.retrieve(query);
        inst.capture("after_retrieval", extractedDocIds(retrieved));

        List<Document> afterMmr = retrieved;
        if (config.isMmrEnabled()) {
            MmrDocumentPostProcessor mmrProc = new MmrDocumentPostProcessor(
                    properties.mmrLambda(), properties.mmrTopK(), properties.fusionTopK(), vectorStoreMapper);
            afterMmr = mmrProc.process(query, retrieved);
        }
        inst.capture("after_mmr", extractedDocIds(afterMmr));

        List<Document> afterRerank = afterMmr;
        if (config.isRerankEnabled() && rerankPostProcessor != null) {
            afterRerank = rerankPostProcessor.process(query, afterMmr);
        }
        inst.capture("after_rerank", extractedDocIds(afterRerank));

        List<Document> afterParent = config.isParentChildEnabled()
                ? parentProcessor.process(query, afterRerank) : afterRerank;
        inst.capture("after_parent_child", extractedDocIds(afterParent));
        return afterParent;
    }

    private RetrievalMetrics computeRetrievalMetrics(EvaluationDatasetItem item, EvalConfig config,
                                                     List<Document> finalDocs) {
        Set<String> relevantIds = item.relevantChunkIds() != null
                ? item.relevantChunkIds() : Set.of();
        int k = config.getTopK() != null ? config.getTopK() : evalProps.getRunner().getDefaultK();
        return metricsCalculator.calculate(extractedDocIds(finalDocs), relevantIds, k);
    }

    private HybridDocumentRetriever createEvalRetriever(EvalConfig config) {
        RagRetrievalProperties evalProps = copyWithOverride(properties, config);
        Long userId = config.getTestUserId() != null
                ? config.getTestUserId() : this.evalProps.getTestUserId();
        // 用 Spring 管理的 ScopedTasks 构造，复用其虚拟线程作用域执行器。
        // 路径组配与生产装配一致（恒含向量 + BM25）；评测侧可覆盖的只是 topK/rrfK 参数。
        List<RetrievalPath> paths = new java.util.ArrayList<>();
        paths.add(new VectorRetrievalPath(vectorStore, evalProps));
        paths.add(new Bm25RetrievalPath(vectorStoreMapper, queryNormalizer, evalProps));
        HybridSearchService evalSearchService = new HybridSearchService(
                paths, evalProps, queryNormalizer, scopedTasks, null);
        return new HybridDocumentRetriever(evalSearchService, userId, null);
    }

    private RagRetrievalProperties copyWithOverride(RagRetrievalProperties original, EvalConfig config) {
        return original.withOverrides(
                config.getVectorTopK(),
                config.getBm25TopK(),
                config.getRrfK()
        );
    }

    private String rewriteQuery(String queryText) {
        try {
            Query originalQuery = new Query(queryText);
            Query transformed = queryTransformer.apply(originalQuery);
            if (transformed != null) {
                return transformed.text();
            }
        } catch (Exception e) {
            log.warn("Query rewrite failed, using original: {}", e);
        }
        return queryText;
    }

    private String generateAnswer(String queryText, List<Document> contextDocs) {
        var prompt = GenerationPrompts.RAG_ANSWER.formatted(
                ContextTextBuilder.build(contextDocs),
                queryText);

        ChatClient chatClient = rewriteClientResolver.resolve(evalProps.getGenerationModel());
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    private List<String> extractedDocIds(List<Document> docs) {
        if (docs == null) return List.of();
        return docs.stream()
                .map(doc -> {
                    Object id = doc.getId();
                    return id != null ? String.valueOf(id) : "";
                })
                .filter(id -> !id.isEmpty())
                .toList();
    }

    /**
     * 评估运行配置（可覆盖 Pipeline 参数）
     */
    public static class EvalConfig {
        private Integer vectorTopK;
        private Integer bm25TopK;
        private Integer rrfK;
        private boolean rerankEnabled = true;
        private boolean mmrEnabled = true;
        private boolean parentChildEnabled = true;
        private boolean queryRewriteEnabled = true;
        private boolean generationEnabled = true;
        private Integer topK;
        private Long testUserId;

        public Integer getVectorTopK() { return vectorTopK; }
        public void setVectorTopK(Integer vectorTopK) { this.vectorTopK = vectorTopK; }

        public Integer getBm25TopK() { return bm25TopK; }
        public void setBm25TopK(Integer bm25TopK) { this.bm25TopK = bm25TopK; }

        public Integer getRrfK() { return rrfK; }
        public void setRrfK(Integer rrfK) { this.rrfK = rrfK; }

        public boolean isRerankEnabled() { return rerankEnabled; }
        public void setRerankEnabled(boolean rerankEnabled) { this.rerankEnabled = rerankEnabled; }

        public boolean isMmrEnabled() { return mmrEnabled; }
        public void setMmrEnabled(boolean mmrEnabled) { this.mmrEnabled = mmrEnabled; }

        public boolean isParentChildEnabled() { return parentChildEnabled; }
        public void setParentChildEnabled(boolean parentChildEnabled) { this.parentChildEnabled = parentChildEnabled; }

        public boolean isQueryRewriteEnabled() { return queryRewriteEnabled; }
        public void setQueryRewriteEnabled(boolean queryRewriteEnabled) { this.queryRewriteEnabled = queryRewriteEnabled; }

        public boolean isGenerationEnabled() { return generationEnabled; }
        public void setGenerationEnabled(boolean generationEnabled) { this.generationEnabled = generationEnabled; }

        public Integer getTopK() { return topK; }
        public void setTopK(Integer topK) { this.topK = topK; }

        public Long getTestUserId() { return testUserId; }
        public void setTestUserId(Long testUserId) { this.testUserId = testUserId; }
    }
}
