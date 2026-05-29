package com.smart.rag.rag.evaluation.runner;

import com.smart.rag.agent.service.HybridSearchService;
import com.smart.rag.rag.chunk.ParentDocumentPostProcessor;
import com.smart.rag.rag.evaluation.config.EvaluationProperties;
import com.smart.rag.rag.evaluation.dataset.DatasetRepository;
import com.smart.rag.rag.evaluation.dataset.EvaluationDatasetItem;
import com.smart.rag.rag.evaluation.metrics.generation.GenerationMetrics;
import com.smart.rag.rag.evaluation.metrics.generation.GenerationMetricsCalculator;
import com.smart.rag.rag.evaluation.metrics.retrieval.RetrievalMetrics;
import com.smart.rag.rag.evaluation.metrics.retrieval.RetrievalMetricsCalculator;
import com.smart.rag.rag.evaluation.result.EvaluationResult;
import com.smart.rag.rag.config.RagRetrievalProperties;
import com.smart.rag.rag.retrieval.BailianRerankPostProcessor;
import com.smart.rag.rag.mapper.VectorStoreMapper;
import com.smart.rag.rag.retrieval.HybridDocumentRetriever;
import com.smart.rag.rag.retrieval.MmrDocumentPostProcessor;
import com.smart.rag.rag.retrieval.QueryNormalizer;
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
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * 评估执行引擎（核心）
 * <p>
 * 直接调用 Pipeline 各组件，在每个阶段之间插入插桩点。
 * 零侵入策略：不修改任何现有 RAG 代码，只调用公共 API。
 * </p>
 */
@Component
@Profile("evaluation")
public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    private final VectorStore vectorStore;
    private final VectorStoreMapper vectorStoreMapper;
    private final JdbcTemplate jdbcTemplate;
    private final RagRetrievalProperties properties;
    private final ParentDocumentPostProcessor parentProcessor;
    private final QueryNormalizer queryNormalizer;
    private final QueryTransformer queryTransformer;
    private final ChatClient.Builder chatClientBuilder;
    private final EvaluationProperties evalProps;
    private final RetrievalMetricsCalculator metricsCalculator;
    private final GenerationMetricsCalculator generationMetricsCalculator;
    private final ObjectMapper objectMapper;
    private final DatasetRepository datasetRepo;

    /** Rerank 单例 Bean（null when rerank-enabled=false），生命周期由 Spring 容器管理 */
    private final BailianRerankPostProcessor rerankPostProcessor;

    public EvaluationRunner(VectorStore vectorStore,
                            VectorStoreMapper vectorStoreMapper,
                            JdbcTemplate jdbcTemplate,
                            RagRetrievalProperties properties,
                            ParentDocumentPostProcessor parentProcessor,
                            QueryNormalizer queryNormalizer,
                            QueryTransformer queryTransformer,
                            ChatClient.Builder chatClientBuilder,
                            EvaluationProperties evalProps,
                            RetrievalMetricsCalculator metricsCalculator,
                            GenerationMetricsCalculator generationMetricsCalculator,
                            ObjectMapper objectMapper,
                            DatasetRepository datasetRepo,
                            @Nullable BailianRerankPostProcessor rerankPostProcessor) {
        this.vectorStore = vectorStore;
        this.vectorStoreMapper = vectorStoreMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.parentProcessor = parentProcessor;
        this.queryNormalizer = queryNormalizer;
        this.queryTransformer = queryTransformer;
        this.chatClientBuilder = chatClientBuilder;
        this.evalProps = evalProps;
        this.metricsCalculator = metricsCalculator;
        this.generationMetricsCalculator = generationMetricsCalculator;
        this.objectMapper = objectMapper;
        this.datasetRepo = datasetRepo;
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
            // 1. 查询规范化
            String normalized = queryNormalizer.normalize(item.question());
            inst.capture("after_normalize", normalized);

            // 2. 查询改写（可选）
            String queryText = normalized;
            if (config.isQueryRewriteEnabled()) {
                queryText = rewriteQuery(normalized);
                queryRewritten = queryText;
            }
            inst.capture("after_rewrite", queryText);

            // 3. 检索阶段
            Query query = new Query(queryText);
            HybridDocumentRetriever retriever = createEvalRetriever(config);
            List<Document> retrieved = retriever.retrieve(query);
            List<String> retrievedIds = extractedDocIds(retrieved);
            inst.capture("after_retrieval", retrievedIds);

            // 4. MMR 阶段（可选） — 先去冗余，减少 Rerank 算力浪费
            List<Document> afterMmr = retrieved;
            if (config.isMmrEnabled()) {
                MmrDocumentPostProcessor mmrProc = new MmrDocumentPostProcessor(
                        properties.mmrLambda(), properties.mmrTopK(), vectorStoreMapper);
                afterMmr = mmrProc.process(query, retrieved);
            }
            inst.capture("after_mmr", extractedDocIds(afterMmr));

            // 5. Rerank 阶段（可选） -- 去冗余后精排，使用注入的单例 Bean
            List<Document> afterRerank = afterMmr;
            if (config.isRerankEnabled() && rerankPostProcessor != null) {
                afterRerank = rerankPostProcessor.process(query, afterMmr);
            }
            inst.capture("after_rerank", extractedDocIds(afterRerank));

            // 6. ParentChild 替换（可选）
            List<Document> afterParent = config.isParentChildEnabled()
                    ? parentProcessor.process(query, afterRerank) : afterRerank;
            inst.capture("after_parent_child", extractedDocIds(afterParent));

            // 7. 检索结果
            retrievedDocIds = extractedDocIds(afterParent);

            // 8. 计算检索指标
            Set<String> relevantIds = item.relevantChunkIds() != null
                    ? item.relevantChunkIds() : Set.of();
            int k = config.getTopK() != null ? config.getTopK() : evalProps.getRunner().getDefaultK();
            retrievalMetrics = metricsCalculator.calculate(
                    extractedDocIds(retrieved), relevantIds, k);

            // 9. LLM 生成 + 生成指标
            if (config.isGenerationEnabled()) {
                generatedAnswer = generateAnswer(queryText, afterParent);
                inst.capture("after_generation", generatedAnswer);

                GenerationMetrics genMetrics = generationMetricsCalculator.calculate(
                        item.question(), generatedAnswer,
                        item.groundTruthAnswer(), afterParent);
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

    private HybridDocumentRetriever createEvalRetriever(EvalConfig config) {
        RagRetrievalProperties evalProps = copyWithOverride(properties, config);
        Long userId = config.getTestUserId() != null
                ? config.getTestUserId() : this.evalProps.getTestUserId();
        // 创建使用覆盖配置的临时 HybridSearchService
        HybridSearchService evalSearchService = new HybridSearchService(
                vectorStore, vectorStoreMapper, evalProps, queryNormalizer,
                Executors.newFixedThreadPool(2));
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
            log.warn("Query rewrite failed, using original: {}", e.getMessage());
        }
        return queryText;
    }

    private String generateAnswer(String queryText, List<Document> contextDocs) {
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < contextDocs.size(); i++) {
            contextBuilder.append("片段").append(i + 1).append("：\n");
            contextBuilder.append(contextDocs.get(i).getText()).append("\n\n");
        }

        String prompt = """
                基于以下检索到的文档片段回答用户的问题。
                如果文档片段中没有相关信息，请如实说明。

                文档片段：
                %s

                用户问题：%s

                回答：""".formatted(contextBuilder.toString(), queryText);

        return chatClientBuilder.build().prompt()
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
