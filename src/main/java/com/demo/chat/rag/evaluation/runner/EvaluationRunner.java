package com.demo.chat.rag.evaluation.runner;

import com.demo.chat.rag.chunk.ParentDocumentPostProcessor;
import com.demo.chat.rag.evaluation.config.EvaluationProperties;
import com.demo.chat.rag.evaluation.dataset.DatasetRepository;
import com.demo.chat.rag.evaluation.dataset.EvaluationDatasetItem;
import com.demo.chat.rag.evaluation.metrics.retrieval.RetrievalMetrics;
import com.demo.chat.rag.evaluation.metrics.retrieval.RetrievalMetricsCalculator;
import com.demo.chat.rag.evaluation.result.EvaluationResult;
import com.demo.chat.rag.evaluation.result.StageSnapshot;
import com.demo.chat.rag.config.RagRetrievalProperties;
import com.demo.chat.rag.retrieval.BailianRerankPostProcessor;
import com.demo.chat.rag.retrieval.HybridDocumentRetriever;
import com.demo.chat.rag.retrieval.MmrDocumentPostProcessor;
import com.demo.chat.rag.retrieval.QueryNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final JdbcTemplate jdbcTemplate;
    private final RagRetrievalProperties properties;
    private final ParentDocumentPostProcessor parentProcessor;
    private final QueryNormalizer queryNormalizer;
    private final QueryTransformer queryTransformer;
    private final ChatClient.Builder chatClientBuilder;
    private final EvaluationProperties evalProps;
    private final RetrievalMetricsCalculator metricsCalculator;
    private final ObjectMapper objectMapper;
    private final DatasetRepository datasetRepo;

    public EvaluationRunner(VectorStore vectorStore,
                            JdbcTemplate jdbcTemplate,
                            RagRetrievalProperties properties,
                            ParentDocumentPostProcessor parentProcessor,
                            QueryNormalizer queryNormalizer,
                            QueryTransformer queryTransformer,
                            ChatClient.Builder chatClientBuilder,
                            EvaluationProperties evalProps,
                            RetrievalMetricsCalculator metricsCalculator,
                            ObjectMapper objectMapper,
                            DatasetRepository datasetRepo) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.parentProcessor = parentProcessor;
        this.queryNormalizer = queryNormalizer;
        this.queryTransformer = queryTransformer;
        this.chatClientBuilder = chatClientBuilder;
        this.evalProps = evalProps;
        this.metricsCalculator = metricsCalculator;
        this.objectMapper = objectMapper;
        this.datasetRepo = datasetRepo;
    }

    /**
     * 执行单条评估
     *
     * @param item         测试数据项
     * @param config       评估配置（可覆盖 Pipeline 参数）
     * @return 评估结果
     */
    public EvaluationResult evaluate(EvaluationDatasetItem item, EvalConfig config) {
        long start = System.currentTimeMillis();
        EvaluationResult result = new EvaluationResult();
        result.setItemId(item.getId());
        result.setItemQuestionSnapshot(item.getQuestion());
        result.setItemGroundTruthSnapshot(item.getGroundTruthAnswer());
        result.setItemRelevantChunkIdsSnapshot(item.getRelevantChunkIds());

        PipelineInstrumenter inst = new PipelineInstrumenter(objectMapper);

        try {
            // 1. 查询规范化
            String normalized = queryNormalizer.normalize(item.getQuestion());
            inst.capture("after_normalize", normalized);

            // 2. 查询改写（可选）
            String queryText = normalized;
            if (config.isQueryRewriteEnabled()) {
                queryText = rewriteQuery(normalized);
                result.setQueryRewritten(queryText);
            }
            inst.capture("after_rewrite", queryText);

            // 3. 检索阶段
            Query query = new Query(queryText);
            HybridDocumentRetriever retriever = createEvalRetriever(config);
            List<Document> retrieved = retriever.retrieve(query);
            List<String> retrievedIds = extractedDocIds(retrieved);
            inst.capture("after_retrieval", retrievedIds);

            // 4. Rerank 阶段（可选）
            List<Document> afterRerank = retrieved;
            if (config.isRerankEnabled()) {
                BailianRerankPostProcessor reranker = createReranker();
                afterRerank = reranker.process(query, retrieved);
            }
            inst.capture("after_rerank", extractedDocIds(afterRerank));

            // 5. MMR 阶段（可选）
            List<Document> afterMmr = afterRerank;
            if (config.isMmrEnabled()) {
                MmrDocumentPostProcessor mmrProc = new MmrDocumentPostProcessor(
                        properties.getMmrLambda(), properties.getMmrTopK());
                afterMmr = mmrProc.process(query, afterRerank);
            }
            inst.capture("after_mmr", extractedDocIds(afterMmr));

            // 6. ParentChild 替换（可选）
            List<Document> afterParent = config.isParentChildEnabled()
                    ? parentProcessor.process(query, afterMmr) : afterMmr;
            inst.capture("after_parent_child", extractedDocIds(afterParent));

            // 7. 设置检索结果
            result.setRetrievedDocIds(extractedDocIds(afterParent));

            // 8. 计算检索指标
            Set<String> relevantIds = item.getRelevantChunkIds() != null
                    ? item.getRelevantChunkIds() : Set.of();
            int k = config.getTopK() != null ? config.getTopK() : evalProps.getRunner().getDefaultK();
            RetrievalMetrics metrics = metricsCalculator.calculate(
                    extractedDocIds(retrieved), // 用原始检索结果算指标
                    relevantIds,
                    k);
            result.setRetrievalMetrics(metrics);

            // 9. LLM 生成（Phase 4 完善生成侧）
            if (config.isGenerationEnabled()) {
                String answer = generateAnswer(queryText, afterParent);
                result.setGeneratedAnswer(answer);
                inst.capture("after_generation", answer);
            }

            // 10. 保存快照
            result.setStageSnapshots(inst.getSnapshots());

        } catch (Exception e) {
            log.error("Evaluation failed for item {}: {}", item.getId(), e.getMessage(), e);
            result.setError(e.getMessage());
            result.setStageSnapshots(inst.getSnapshots());
        }

        result.setLatencyMs((int) (System.currentTimeMillis() - start));
        return result;
    }

    /**
     * 创建 Reranker（零侵入：从 properties 读参数后 new 创建）
     */
    private BailianRerankPostProcessor createReranker() {
        return new BailianRerankPostProcessor(
                properties.getRerankBaseUrl(),
                properties.getRerankApiKey(),
                properties.getRerankModel(),
                properties.getRerankTopN()
        );
    }

    /**
     * 创建评估专用的 Retriever（可覆盖运行时参数）
     * <p>
     * 零侵入策略：不修改 HybridDocumentRetriever，创建新实例注入覆盖参数。
     * </p>
     */
    private HybridDocumentRetriever createEvalRetriever(EvalConfig config) {
        RagRetrievalProperties evalProps = copyWithOverride(properties, config);
        Long userId = config.getTestUserId() != null
                ? config.getTestUserId() : this.evalProps.getTestUserId();
        return new HybridDocumentRetriever(
                vectorStore, jdbcTemplate, evalProps,
                queryNormalizer, userId, null, objectMapper);
    }

    /**
     * 复制 Properties 并覆盖评估配置
     */
    private RagRetrievalProperties copyWithOverride(RagRetrievalProperties original, EvalConfig config) {
        RagRetrievalProperties copy = new RagRetrievalProperties();
        // 复制原始配置
        copy.setQueryRewriteEnabled(original.isQueryRewriteEnabled());
        copy.setHybridRetrievalEnabled(original.isHybridRetrievalEnabled());
        copy.setFtsConfig(original.getFtsConfig());
        copy.setVectorTopK(original.getVectorTopK());
        copy.setBm25TopK(original.getBm25TopK());
        copy.setRrfK(original.getRrfK());
        copy.setRerankEnabled(original.isRerankEnabled());
        copy.setRerankBaseUrl(original.getRerankBaseUrl());
        copy.setRerankApiKey(original.getRerankApiKey());
        copy.setRerankModel(original.getRerankModel());
        copy.setRerankTopN(original.getRerankTopN());
        copy.setMmrEnabled(original.isMmrEnabled());
        copy.setMmrLambda(original.getMmrLambda());
        copy.setMmrTopK(original.getMmrTopK());
        copy.setSimilarityThreshold(original.getSimilarityThreshold());

        // 应用覆盖
        if (config.getVectorTopK() != null) copy.setVectorTopK(config.getVectorTopK());
        if (config.getBm25TopK() != null) copy.setBm25TopK(config.getBm25TopK());
        if (config.getRrfK() != null) copy.setRrfK(config.getRrfK());

        return copy;
    }

    /**
     * 查询改写
     */
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

    /**
     * LLM 生成回答
     */
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

    /**
     * 从文档列表提取 ID 列表
     */
    private List<String> extractedDocIds(List<Document> docs) {
        if (docs == null) return List.of();
        return docs.stream()
                .map(doc -> {
                    // Document ID 可能是 UUID 字符串
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

        // ======================== Getters & Setters ========================

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
