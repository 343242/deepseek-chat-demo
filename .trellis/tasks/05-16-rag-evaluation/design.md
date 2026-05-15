# RAG 评估系统设计文档

**日期**: 2026-05-16
**分支**: rag-dev
**状态**: 审查修正版
**审查记录**: 对照 RAGAS 框架审查，修正 P0×6 + P1×5 问题

---

## 背景

当前 RAG 模块已实现六阶段检索 Pipeline：
```
用户查询 → QueryNormalizer → RewriteQueryTransformer
         → HybridDocumentRetriever(pgvector + BM25 + RRF)
         → BailianRerankPostProcessor(qwen3-rerank)
         → MmrDocumentPostProcessor
         → ParentDocumentPostProcessor
         → LLM 生成回答
```

但没有量化手段评估各环节效果。当前优化全凭直觉，无法回答：
- 混合检索 vs 纯向量检索，recall 差多少？
- Rerank 后 Precision 提升了还是下降了？
- 不同 chunk 策略对回答忠实度的影响？
- 参数调优（topK、rrfK、lambda）的实际收益？

## 目标

建立数据驱动的 RAG 评估体系：

1. **检索侧评估**：量化 Recall@K、Precision@K、MRR、NDCG、Context Precision
2. **生成侧评估**：量化 Faithfulness（忠实度）、Context Recall（上下文召回率）、Answer Relevance（回答相关性）、Context Relevance（上下文相关性）
3. **Pipeline 阶段对比**：对比 Rerank 前后、MMR 前后、ParentChild 前后的指标变化
4. **参数调优支撑**：同一测试集、不同参数配置的 A/B 对比
5. **趋势追踪**：历次评估结果可追溯，评估代码变更的影响

## 设计原则

### P1: 零侵入现有 Pipeline

- 评估模块是独立的 Spring `@Component`，不修改任何现有 RAG 代码
- 直接调用现有 Bean 的公共 API，非 Spring Bean 的组件（如 BailianRerankPostProcessor、MmrDocumentPostProcessor）通过 `new` 创建实例
- 评估 API 仅在 `evaluation` profile 激活时可用，生产环境默认关闭

### P2: LLM-as-Judge 分离

- 生成侧指标由独立的 Judge 模型评估
- 生成模型 ≠ Judge 模型（如生成用 DeepSeek V4 Pro，Judge 用 GLM-5）
- Judge 模型通过 Spring AI ChatClient 调用，复用现有模型路由基础设施

### P3: 可复现

- 每次评估运行有唯一 runId，记录完整配置快照
- 测试数据集版本化，可追溯
- 评估结果持久化到 PostgreSQL，支持历史对比

---

## 模块结构

```
com.demo.chat.rag.evaluation
├── config/
│   ├── EvaluationProperties.java          // 评估配置
│   └── EvaluationConfig.java              // Bean 注册 + profile 条件
├── dataset/
│   ├── EvaluationDataset.java             // 数据集实体
│   ├── EvaluationDatasetItem.java         // 单条测试项
│   ├── DatasetGenerator.java              // LLM 自动生成数据集
│   ├── DatasetRepository.java             // 数据集持久化（JdbcTemplate）
│   └── DatasetExporter.java               // 导出为 JSON（人工审核用）
├── runner/
│   ├── EvaluationRunner.java              // 评估执行引擎（核心）
│   ├── PipelineInstrumenter.java          // Pipeline 阶段插桩，捕获中间结果
│   └── EvaluationRun.java                 // 单次评估运行记录
├── metrics/
│   ├── retrieval/
│   │   ├── RecallAtK.java                 // Recall@K
│   │   ├── PrecisionAtK.java             // Precision@K
│   │   ├── MRR.java                       // Mean Reciprocal Rank
│   │   ├── NDCG.java                      // Normalized DCG
│   │   ├── ContextPrecisionCalculator.java // 上下文精确率（RAGAS 对齐）
│   │   └── RetrievalMetricsCalculator.java // 检索指标聚合（micro-average）
│   └── generation/
│       ├── FaithfulnessScorer.java        // 忠实度（两步：extractClaims + verifyClaims）
│       ├── ContextRecallScorer.java       // 上下文召回率（ground truth claims → context 支撑）
│       ├── AnswerRelevanceScorer.java     // 回答相关性（embedding cosine 相似度）
│       ├── ContextRelevanceScorer.java    // 上下文相关性（LLM-as-Judge）
│       └── GenerationMetricsCalculator.java // 生成指标聚合
├── judge/
│   ├── LlmJudge.java                      // LLM-as-Judge 接口
│   ├── LlmJudgeImpl.java                  // 基于ChatClient实现
│   └── JudgePrompt.java                   // Judge prompt 模板
├── result/
│   ├── EvaluationResult.java              // 单条评估结果
│   ├── StageSnapshot.java                 // Pipeline 阶段快照
│   ├── EvaluationReport.java              // 评估报告（聚合）
│   └── EvaluationResultRepository.java    // 结果持久化
└── controller/
    └── EvaluationController.java          // REST API
```

---

## 数据模型

### 评估数据集（evaluation_dataset）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL | PK |
| name | VARCHAR(200) | 数据集名称 |
| description | TEXT | 说明 |
| version | INT | 版本号 |
| source | VARCHAR(50) | 来源：llm_generated / manual / hybrid |
| judge_model | VARCHAR(100) | 使用的 Judge 模型 |
| item_count | INT | 测试条目数 |
| created_at | TIMESTAMPTZ | 创建时间 |
| updated_at | TIMESTAMPTZ | 更新时间 |

### 评估数据项（evaluation_dataset_item）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL | PK |
| dataset_id | BIGINT | FK → evaluation_dataset.id |
| question | TEXT | 用户问题 |
| ground_truth_answer | TEXT | 标准答案（可选，检索评估不需要） |
| relevant_chunk_ids | TEXT[] | 相关 chunk 的 ID 列表（检索 ground truth，可包含多个 chunk） |
| relevant_content | TEXT | 相关文档内容（用于 context relevance/recall） |
| tags | VARCHAR(100)[] | 标签（按主题/难度分类） |
| status | VARCHAR(20) | draft / reviewed / approved |
| seq | INT | 序号 |

### 评估运行（evaluation_run）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL | PK |
| dataset_id | BIGINT | FK → evaluation_dataset.id |
| name | VARCHAR(200) | 运行名称 |
| config_snapshot | JSONB | 配置快照（topK、rrfK、lambda 等全部参数） |
| status | VARCHAR(20) | pending / running / completed / failed |
| generation_model | VARCHAR(100) | 生成模型 |
| judge_model | VARCHAR(100) | Judge 模型 |
| summary | JSONB | 聚合指标结果 |
| started_at | TIMESTAMPTZ | 开始时间 |
| completed_at | TIMESTAMPTZ | 完成时间 |

### 评估结果（evaluation_result）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL | PK |
| run_id | BIGINT | FK → evaluation_run.id |
| item_id | BIGINT | FK → evaluation_dataset_item.id |
| item_question_snapshot | TEXT | 评估时的问题快照（防止后续修改导致历史报告失效） |
| item_ground_truth_snapshot | TEXT | 评估时的标准答案快照 |
| item_relevant_chunk_ids_snapshot | TEXT[] | 评估时的相关 chunk ID 快照 |
| query_rewritten | TEXT | 改写后的查询 |
| retrieved_doc_ids | TEXT[] | 检索到的文档 ID |
| generated_answer | TEXT | LLM 生成的回答 |
| stage_snapshots | JSONB | 各阶段快照（含文档列表 + 各阶段耗时 ms） |
| retrieval_metrics | JSONB | 检索指标（recall, precision, mrr, ndcg, context_precision） |
| generation_metrics | JSONB | 生成指标（faithfulness, context_recall, answer_relevance, context_relevance），Judge 失败时为 null |
| error | TEXT | 错误信息（如失败） |
| latency_ms | INT | 端到端延迟 |

---

## 核心设计

### 1. 评估执行引擎（EvaluationRunner）

**核心思路**：不通过 ChatAdvisorChainFactory → RagAdvisorFactory 的完整聊天链路，而是直接调用 Pipeline 各组件，在每个阶段之间插入插桩点。

```java
@Component
@Profile("evaluation")
public class EvaluationRunner {

    // 注入 Spring Bean（@Component）
    private final QueryNormalizer queryNormalizer;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ParentDocumentPostProcessor parentProcessor; // @Component
    private final ChatClient.Builder chatClientBuilder;
    private final RagRetrievalProperties properties;
    private final LlmJudge judge;
    private final EvaluationProperties evalProps;
    private final ObjectMapper objectMapper;

    /**
     * 注意：BailianRerankPostProcessor 和 MmrDocumentPostProcessor 不是 Spring Bean，
     * 在 RagAdvisorFactory 中用 new 创建。评估时也需要 new 创建，保持一致。
     * 这是零侵入策略的关键：不为了评估而给这些类加 @Component。
     */

    public EvaluationResult evaluate(EvaluationDatasetItem item, EvalConfig config) {
        long start = System.currentTimeMillis();
        PipelineInstrumenter inst = new PipelineInstrumenter();

        // 1. 查询规范化
        String normalized = queryNormalizer.normalize(item.getQuestion());
        inst.capture("after_normalize", normalized);

        // 2. 构建带评估配置的 Retriever（可覆盖 topK、rrfK 等）
        Query query = new Query(normalized);
        HybridDocumentRetriever retriever = createEvalRetriever(config);

        // 3. 检索阶段
        List<Document> retrieved = retriever.retrieve(query);
        inst.capture("after_retrieval", retrieved);

        // 4. Rerank 阶段（可选）— 每次 new 创建，零侵入
        List<Document> afterRerank = retrieved;
        if (config.isRerankEnabled()) {
            BailianRerankPostProcessor reranker = createReranker();
            afterRerank = reranker.process(query, retrieved);
        }
        inst.capture("after_rerank", afterRerank);

        // 5. MMR 阶段（可选）— 每次 new 创建，零侵入
        List<Document> afterMmr = afterRerank;
        if (config.isMmrEnabled()) {
            MmrDocumentPostProcessor mmrProc = new MmrDocumentPostProcessor(
                properties.getMmrLambda(), properties.getMmrTopK());
            afterMmr = mmrProc.process(query, afterRerank);
        }
        inst.capture("after_mmr", afterMmr);

        // 6. ParentChild 替换（可选）
        List<Document> afterParent = config.isParentChildEnabled()
            ? parentProcessor.process(query, afterMmr) : afterMmr;
        inst.capture("after_parent_child", afterParent);

        // 7. LLM 生成
        String answer = generateAnswer(query.text(), afterParent, config.getGenerationModel());
        inst.capture("after_generation", answer);

        // 8. 计算指标
        long latency = System.currentTimeMillis() - start;
        return buildResult(item, inst, answer, afterParent, latency);
    }

    /**
     * 创建 Reranker — 从 RagRetrievalProperties 读取配置，
     * 与 RagAdvisorFactory 中 getPostProcessors() 保持一致。
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
     *
     * 关键：不修改现有 HybridDocumentRetriever，
     * 而是新建实例注入评估配置覆盖的参数。
     */
    private HybridDocumentRetriever createEvalRetriever(EvalConfig config) {
        // 创建参数覆盖的 properties 副本
        RagRetrievalProperties evalProps = copyWithOverride(properties, config);

        return new HybridDocumentRetriever(
            vectorStore, jdbcTemplate, evalProps,
            queryNormalizer, config.getTestUserId(), null,
            objectMapper
        );
    }
}
```

**关键设计**：
- `HybridDocumentRetriever` 是非 Spring 管理的 POJO（无 `@Component`），通过构造器注入依赖，评估时直接 `new` 即可
- `BailianRerankPostProcessor` 和 `MmrDocumentPostProcessor` 也不是 Spring Bean，评估时从 `RagRetrievalProperties` 读取参数后 `new` 创建（**不为了评估而加 @Component**）
- `ParentDocumentPostProcessor` 是 `@Component`（由 `RagConfig` 注册），可直接注入
- 各阶段捕获完整快照（文档列表 + 时间戳 + 各阶段耗时 ms），用于阶段对比和瓶颈定位
- `EvalConfig` 支持覆盖任意参数，同一数据集可跑不同配置的 A/B 对比

### 2. Pipeline 阶段插桩（PipelineInstrumenter）

```java
public class PipelineInstrumenter {

    private final List<StageSnapshot> snapshots = new ArrayList<>();
    private final long startTimeMs;

    public PipelineInstrumenter() {
        this.startTimeMs = System.currentTimeMillis();
    }

    public void capture(String stageName, Object data) {
        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        snapshots.add(new StageSnapshot(
            stageName,
            serialize(data),
            System.currentTimeMillis(),
            elapsedMs  // 记录距开始的耗时
        ));
    }

    // ... getDocumentsAtStage / getDocIdsAtStage 同前 ...

    /**
     * 获取两个阶段之间的耗时
     */
    public long getLatencyBetweenStages(String fromStage, String toStage) {
        StageSnapshot from = findStage(fromStage);
        StageSnapshot to = findStage(toStage);
        if (from == null || to == null) return -1;
        return to.timestampMs() - from.timestampMs();
    }
}
```

### 3. 检索指标计算

#### Recall@K
```
Recall@K = |检索到的相关文档 ∩ ground truth| / |ground truth|
```

#### Precision@K
```
Precision@K = |检索到的相关文档 ∩ ground truth| / K
```

#### MRR (Mean Reciprocal Rank)
```
MRR = 1/N × Σ(1 / rank_i)    // rank_i = 第一个相关文档的排名
```

#### NDCG (Normalized Discounted Cumulative Gain)
```
DCG@K  = Σ (2^rel_i - 1) / log2(i + 1)
NDCG@K = DCG@K / IDCG@K       // IDCG = 理想排序的 DCG
```

#### Context Precision（上下文精确率，对齐 RAGAS）

衡量检索结果中**相关文档是否排在前面**，是 RAG 场景专用的排序质量指标。

```
Context Precision = Σ(k=1..K) (Precision@k × rel(k)) / Σ(k=1..K) rel(k)
```

其中 `rel(k) = 1` 表示第 k 个检索结果是相关的（在 ground truth 中），`Precision@k = 前 k 个结果中相关文档数 / k`。

如果所有检索结果都不相关，Context Precision = 0。

**与 NDCG 的区别**：Context Precision 更关注"相关文档是否排在前面"这一 RAG 特定问题——对噪声文档排在相关文档前面更敏感。

**实现策略**：
- 相关性分级：ground truth 中的文档 rel=1，其余 rel=0（简化版，与 RAGAS NonLLMContextPrecisionWithReference 一致）
- 未来可扩展为 rel=2（高度相关）/ rel=1（相关）/ rel=0（不相关）

```java
public class RetrievalMetricsCalculator {

    /**
     * 聚合模式：与 RAGAS 一致使用 micro-average
     * micro = 全局 TP/FP/FN 汇总后算分数
     * macro = 每条单独算分后取平均
     */
    public enum AggregateMode { MICRO, MACRO }

    public RetrievalMetrics calculate(
            List<String> retrievedIds,
            Set<String> relevantIds,
            int k) {

        List<String> topK = retrievedIds.subList(0, Math.min(k, retrievedIds.size()));

        Set<String> hits = new HashSet<>(topK);
        hits.retainAll(relevantIds);

        double recall = relevantIds.isEmpty() ? 0 : (double) hits.size() / relevantIds.size();
        double precision = topK.isEmpty() ? 0 : (double) hits.size() / (double) topK.size();
        double mrr = computeMRR(retrievedIds, relevantIds);
        double ndcg = computeNDCG(retrievedIds, relevantIds, k);
        double contextPrecision = computeContextPrecision(topK, relevantIds);

        return new RetrievalMetrics(recall, precision, mrr, ndcg, contextPrecision);
    }

    // MRR 和 NDCG 实现同前，略...

    /**
     * Context Precision（对齐 RAGAS NonLLMContextPrecisionWithReference）
     *
     * 公式: Σ(k=1..K) (Precision@k × rel(k)) / Σ(k=1..K) rel(k)
     *
     * 特点：如果相关文档排在前面，分数高；
     *       如果相关文档排在后面或混在噪声中，分数低。
     */
    private double computeContextPrecision(List<String> topK, Set<String> relevantIds) {
        int relevantCount = 0;
        double weightedPrecisionSum = 0;

        for (int k = 0; k < topK.size(); k++) {
            if (relevantIds.contains(topK.get(k))) {
                relevantCount++;
                double precisionAtK = (double) relevantCount / (k + 1);
                weightedPrecisionSum += precisionAtK;
            }
        }

        return relevantCount == 0 ? 0 : weightedPrecisionSum / relevantCount;
    }
}
```

### 4. 生成侧指标（LLM-as-Judge + Embedding）

**统一评分尺度**：所有生成侧指标归一化到 **0-1 范围**，便于横向对比和聚合。

#### Faithfulness（忠实度）— 两步分离

**衡量**：生成的回答是否仅基于检索到的上下文，没有"幻觉"。

**方法（对齐 RAGAS）**：
1. **Step 1 — Claims 提取**：从 answer 中提取所有独立声明（一次 LLM 调用）
2. **Step 2 — Claims 验证**：对每个 claim 判断是否可从 context 中推导（一次 LLM 调用，批量验证）
3. Faithfulness = 可推导的 claims 数 / 总 claims 数（范围 0-1）

**Step 1 Prompt（extractClaims）**：
```
给定以下回答，提取其中所有事实性声明。
每个声明应是一个独立的、可验证的事实陈述。

回答：
{answer}

输出 JSON 数组（不要输出其他内容）：
[
  "声明1",
  "声明2",
  ...
]
```

**Step 2 Prompt（verifyClaims）**：
```
给定以下上下文（检索到的文档片段）和一组声明。
判断每个声明是否可以从上下文中推导出来。

上下文：
{context}

声明：
{claims_json}

输出 JSON（不要输出其他内容）：
{
  "verifications": [
    {"claim": "...", "supported": true, "evidence": "上下文中提到..."},
    {"claim": "...", "supported": false, "reason": "上下文中未提及..."}
  ],
  "faithfulness_score": 0.85
}
```

**为什么两步分离**：长回答可能包含 10+ claims，合并到一个 prompt 容易截断或遗漏。分离后 Step 1 的输出可以直接传给 Step 2，确保完整性。

#### Context Recall（上下文召回率）— RAGAS 核心指标

**衡量**：检索到的上下文是否足够完整地覆盖了标准答案的所有要点。

**注意**：这不是检索层的 Recall@K，而是**生成侧指标**。方向与 Faithfulness 相反：
- Faithfulness：回答中的每个 claim 是否有 context 支撑？（回答 → context）
- Context Recall：标准答案中的每个 claim 是否有 context 支撑？（标准答案 → context）

**方法（对齐 RAGAS Context Recall）**：
1. 从 ground_truth_answer 中提取 claims（复用 extractClaims prompt）
2. 对每个 claim 判断是否可从检索到的 context 中推导
3. Context Recall = 可推导的 claims 数 / 总 claims 数（范围 0-1）

**Prompt**：复用 Faithfulness 的两步流程，只是将 answer 替换为 ground_truth_answer。

#### Answer Relevance（回答相关性）— Embedding 相似度

**衡量**：回答是否直接回应了用户的问题。

**方法（对齐 RAGAS Answer Relevancy）**：
1. 从 answer 中反向生成 N 个可能的问题（LLM 调用）
2. 计算每个生成问题与原始问题的 embedding cosine 相似度
3. Answer Relevance = 平均 cosine 相似度（范围 0-1）

**为什么不用 LLM 打分**：LLM 1-5 打分引入过多 Judge 偏差（不同模型、不同 prompt 打分差异大）。embedding 相似度更客观、可复现。

**反向生成问题的 Prompt**：
```
给定以下回答，生成 3 个该回答可能回应的问题。
问题应该简洁、具体。

回答：
{answer}

输出 JSON 数组（不要输出其他内容）：
[
  "问题1",
  "问题2",
  "问题3"
]
```

**实现**：
```java
public class AnswerRelevanceScorer {

    private final LlmJudge judge;
    private final EmbeddingModel embeddingModel; // 复用 DashScope Embedding

    public double score(String question, String answer) {
        // Step 1: 从 answer 反向生成问题
        List<String> generatedQuestions = judge.generateQuestions(answer);

        // Step 2: 计算 embedding 相似度
        float[] originalEmbedding = embeddingModel.embed(question);
        double totalSimilarity = 0;
        int count = 0;

        for (String genQ : generatedQuestions) {
            float[] genEmbedding = embeddingModel.embed(genQ);
            totalSimilarity += cosineSimilarity(originalEmbedding, genEmbedding);
            count++;
        }

        return count == 0 ? 0 : totalSimilarity / count;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
```

#### Context Relevance（上下文相关性）— LLM-as-Judge

**衡量**：检索到的上下文对回答问题的有用程度。

**评分**：归一化到 0-1（(score-1)/4 后映射）。

**Judge Prompt（含 Few-Shot）**：
```
给定以下用户问题和检索到的文档片段，评估每个片段对回答问题的有用程度。

示例：
问题：什么是 RAG？
片段1："RAG（Retrieval-Augmented Generation）是一种结合检索和生成的 AI 技术。"
→ usefulness: 5（直接包含答案）
片段2："Transformer 架构由 Google 在 2017 年提出。"
→ usefulness: 1（与问题无关）

---

问题：{question}

文档片段：
{context_chunks}

请评估每个文档片段的有用程度。
评分标准：
- 5分：直接包含答案
- 4分：高度相关，提供重要线索
- 3分：部分相关，提供背景信息
- 2分：轻微相关
- 1分：完全无关

输出 JSON（不要输出其他内容）：
{
  "chunk_scores": [
    {"chunk_index": 0, "usefulness": 4, "reason": "..."},
    {"chunk_index": 1, "usefulness": 1, "reason": "..."}
  ],
  "useful_chunk_ratio": 0.6
}
```

**实现说明**：
- `useful_chunk_ratio` = 有用分数 >= 3 的 chunk 数 / 总 chunk 数，归一化到 0-1
- 这个指标直接衡量检索质量（噪声比例），对 MMR/Rerank 效果评估特别有价值

### 5. LLM Judge 实现

```java
@Component
@Profile("evaluation")
public class LlmJudgeImpl implements LlmJudge {

    private static final int MAX_RETRIES = 2;
    private final ChatClient judgeClient;
    private final String judgeModel;
    private final ObjectMapper objectMapper;

    public LlmJudgeImpl(ChatClient.Builder builder, EvaluationProperties props, ObjectMapper objectMapper) {
        this.judgeModel = props.getJudgeModel();
        this.judgeClient = builder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public JudgeVerdict evaluate(String prompt) {
        Exception lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                String response = judgeClient.prompt()
                    .user(prompt)
                    .options(ChatOptionsBuilder.builder()
                        .withModel(judgeModel)
                        .withTemperature(0.0)
                        .build())
                    .call()
                    .content();
                return parseVerdict(response);
            } catch (Exception e) {
                lastError = e;
                log.warn("Judge attempt {} failed: {}", attempt + 1, e.getMessage());
            }
        }
        return JudgeVerdict.failed(lastError.getMessage());
    }

    /**
     * 多层 JSON 提取策略：
     * 1. 直接解析 raw JSON
     * 2. 提取 ```json ... ``` 代码块
     * 3. 正则提取最外层 { ... }
     */
    private JudgeVerdict parseVerdict(String raw) {
        String json = extractJson(raw);
        try {
            return objectMapper.readValue(json, JudgeVerdict.class);
        } catch (Exception e) {
            log.warn("Failed to parse judge verdict: raw='{}', error={}",
                raw.substring(0, Math.min(200, raw.length())), e.getMessage());
            return JudgeVerdict.failed("JSON parse error: " + e.getMessage());
        }
    }

    private String extractJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) return trimmed;
        var matcher = Pattern.compile("```json\\s*\\n([\\s\\S]*?)\\n\\s*```").matcher(raw);
        if (matcher.find()) return matcher.group(1).trim();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) return raw.substring(start, end + 1);
        return trimmed;
    }
}
```

**关键设计**：
- temperature=0：Judge 需要确定性输出，减少评分波动
- 最多重试 2 次：应对 API 临时错误
- 三层 JSON 解析容错：LLM 输出不可控，必须健壮处理
- **Judge 失败不阻塞检索指标**：generation_metrics 标记为 null，retrieval_metrics 正常返回

### 6. 测试数据集生成（DatasetGenerator）

**流程**：
1. 从 `vector_store` 表按 userId 随机采样 N 个文档 chunk
2. 对每个 chunk，调用 LLM 生成 2-3 个问题（附带标准答案和 chunk ID 作为 ground truth）
3. 生成结果标记为 `draft` 状态
4. 导出为 JSON 供人工审核
5. 人工审核修正后导入，标记为 `approved`

**生成 Prompt**：
```
给定以下文档片段，生成 2 个该片段可以回答的问题。
要求：
- 问题应多样化：包括事实查询、概括总结、推理分析
- 每个问题附带简短的标准答案
- 标注难度：easy（直接引用）、medium（需要概括）、hard（需要推理）

文档片段：
{chunk_content}

输出 JSON 数组（不要输出其他内容）：
[
  {
    "question": "...",
    "ground_truth_answer": "...",
    "difficulty": "easy",
    "tags": ["事实查询"]
  }
]
```

**数据集覆盖率策略**：
- 按文档类型（PDF/DOCX/PPTX/XLSX/TXT）分层采样
- 按内容长度分层（短/中/长 chunk）
- 至少 30 条测试项（统计学最小可信样本量）
- 每次采样去重（已采样过的 chunk 不重复）

---

## 配置设计

### EvaluationProperties

```yaml
app:
  evaluation:
    enabled: false                           # 默认关闭
    judge-model: zai/glm-5.1                # Judge 模型
    generation-model: deepseek/deepseek-v4-pro  # 生成模型
    test-user-id: 1                          # 评估使用的测试用户 ID
    dataset:
      sample-size: 50                        # LLM 自动生成时的采样 chunk 数
      questions-per-chunk: 2                 # 每个 chunk 生成的问题数
    runner:
      default-k: 10                          # 默认评估的 topK
      concurrency: 1                         # 并发评估数（避免打爆 API）
      timeout-seconds: 300                   # 单条超时
```

### Profile 隔离

```java
@Configuration
@Profile("evaluation")
@ConditionalOnProperty(name = "app.evaluation.enabled", havingValue = "true")
public class EvaluationConfig {
    // 所有评估相关 Bean 只在 evaluation profile 激活时注册
}
```

---

## 数据库迁移

### V{n}__rag_evaluation.sql

```sql
-- 评估数据集
CREATE TABLE evaluation_dataset (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    version     INT NOT NULL DEFAULT 1,
    source      VARCHAR(50) NOT NULL DEFAULT 'hybrid',
    judge_model VARCHAR(100),
    item_count  INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 评估数据项
CREATE TABLE evaluation_dataset_item (
    id                  BIGSERIAL PRIMARY KEY,
    dataset_id          BIGINT NOT NULL REFERENCES evaluation_dataset(id) ON DELETE CASCADE,
    question            TEXT NOT NULL,
    ground_truth_answer TEXT,
    relevant_chunk_ids  TEXT[],          -- 可包含多个 chunk（跨文档关联）
    relevant_content    TEXT,
    tags                VARCHAR(100)[],
    status              VARCHAR(20) NOT NULL DEFAULT 'draft',
    seq                 INT NOT NULL DEFAULT 0,
    UNIQUE(dataset_id, seq)
);
CREATE INDEX idx_eval_item_dataset ON evaluation_dataset_item(dataset_id);

-- 评估运行
CREATE TABLE evaluation_run (
    id              BIGSERIAL PRIMARY KEY,
    dataset_id      BIGINT NOT NULL REFERENCES evaluation_dataset(id),
    name            VARCHAR(200),
    config_snapshot JSONB,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    generation_model VARCHAR(100),
    judge_model     VARCHAR(100),
    summary         JSONB,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_eval_run_dataset ON evaluation_run(dataset_id);
CREATE INDEX idx_eval_run_status ON evaluation_run(status);

-- 评估结果
CREATE TABLE evaluation_result (
    id                              BIGSERIAL PRIMARY KEY,
    run_id                          BIGINT NOT NULL REFERENCES evaluation_run(id) ON DELETE CASCADE,
    item_id                         BIGINT NOT NULL REFERENCES evaluation_dataset_item(id),
    item_question_snapshot          TEXT,           -- 评估时的问题快照
    item_ground_truth_snapshot      TEXT,           -- 评估时的标准答案快照
    item_relevant_chunk_ids_snapshot TEXT[],        -- 评估时的相关 chunk ID 快照
    query_rewritten                 TEXT,
    retrieved_doc_ids               TEXT[],
    generated_answer                TEXT,
    stage_snapshots                 JSONB,          -- 含各阶段文档列表 + 各阶段耗时 ms
    retrieval_metrics               JSONB,          -- recall, precision, mrr, ndcg, context_precision
    generation_metrics              JSONB,          -- faithfulness, context_recall, answer_relevance, context_relevance
    error                           TEXT,
    latency_ms                      INT
);
CREATE INDEX idx_eval_result_run ON evaluation_result(run_id);
```

---

## REST API

### 数据集管理

```
POST   /api/evaluation/datasets/generate              # LLM 自动生成数据集
GET    /api/evaluation/datasets?page=0&size=20         # 列出数据集（分页）
GET    /api/evaluation/datasets/{id}                   # 数据集详情
GET    /api/evaluation/datasets/{id}/export            # 导出为 JSON（人工审核）
POST   /api/evaluation/datasets/{id}/import            # 导入审核后的 JSON
PUT    /api/evaluation/datasets/{id}/items/{itemId}    # 更新单条数据项（审核修正）
```

### 评估运行

```
POST   /api/evaluation/runs                            # 启动评估运行
GET    /api/evaluation/runs?page=0&size=20&status=completed  # 列出运行（分页+过滤）
GET    /api/evaluation/runs/{id}                       # 运行详情 + 聚合指标
GET    /api/evaluation/runs/{id}/results?page=0&size=50 # 逐条结果（分页）
GET    /api/evaluation/runs/compare?ids=1,2,3          # 多次运行对比
```

### 启动评估运行请求体

```json
{
  "datasetId": 1,
  "name": "baseline-run-001",
  "configOverride": {
    "vectorTopK": 10,
    "bm25TopK": 10,
    "rrfK": 60,
    "rerankEnabled": true,
    "mmrEnabled": true,
    "parentChildEnabled": true,
    "queryRewriteEnabled": true
  }
}
```

---

## 评估报告格式

### 单次运行报告

```json
{
  "runId": 1,
  "datasetName": "baseline-v1",
  "configSnapshot": { ... },
  "summary": {
    "totalItems": 30,
    "successItems": 29,
    "failedItems": 1,
    "avgLatencyMs": 2340,
    "retrieval": {
      "recall@5": 0.72,
      "recall@10": 0.85,
      "precision@5": 0.48,
      "precision@10": 0.35,
      "mrr": 0.68,
      "ndcg@10": 0.73,
      "contextPrecision": 0.78
    },
    "generation": {
      "faithfulness": 0.82,
      "contextRecall": 0.75,
      "answerRelevance": 0.88,
      "contextRelevance": 0.70
    },
    "stageComparison": {
      "afterRetrieval":    { "recall@10": 0.85, "ndcg@10": 0.70, "contextPrecision": 0.65 },
      "afterRerank":       { "recall@10": 0.85, "ndcg@10": 0.78, "contextPrecision": 0.82 },
      "afterMmr":          { "recall@10": 0.82, "ndcg@10": 0.76, "contextPrecision": 0.80 },
      "afterParentChild":  { "recall@10": 0.82, "ndcg@10": 0.76, "contextPrecision": 0.80 }
    },
    "stageLatencyMs": {
      "normalize": 5,
      "retrieval": 320,
      "rerank": 850,
      "mmr": 15,
      "parentChild": 45,
      "generation": 2100,
      "judge": 1800
    }
    }
  }
}
```

### 多次运行对比

```json
{
  "runs": [
    { "name": "baseline", "recall@10": 0.85, "ndcg@10": 0.73, "faithfulness": 0.82, "contextRecall": 0.75, "answerRelevance": 0.88 },
    { "name": "no-rerank", "recall@10": 0.85, "ndcg@10": 0.64, "faithfulness": 0.79, "contextRecall": 0.70, "answerRelevance": 0.85 },
    { "name": "topK-20", "recall@10": 0.92, "ndcg@10": 0.71, "faithfulness": 0.80, "contextRecall": 0.73, "answerRelevance": 0.86 }
  ],
  "delta": {
    "no-rerank vs baseline": { "ndcg@10": -0.09, "faithfulness": -0.03 },
    "topK-20 vs baseline":   { "recall@10": +0.07, "ndcg@10": -0.02 }
  }
}
```

---

## 侵入性分析

### 不修改的文件（零侵入）

| 文件 | 说明 |
|------|------|
| HybridDocumentRetriever | 评估 new 新实例，不注入 |
| BailianRerankPostProcessor | 评估 new 新实例，不注入（非 @Component） |
| MmrDocumentPostProcessor | 评估 new 新实例，不注入（非 @Component） |
| ParentDocumentPostProcessor | 注入 Spring Bean（@Component） |
| QueryNormalizer | 注入 Spring Bean（@Component） |
| RagAdvisorFactory | 评估不经过此工厂 |
| ChatAdvisorChainFactory | 评估不经过聊天链路 |
| RagConfig | 不修改 |
| RagRetrievalProperties | 不修改（只读取，创建副本注入覆盖参数） |

### 新增的文件

| 文件 | 说明 |
|------|------|
| `evaluation/` 整个包 | 全部新增，约 20 个文件 |
| `V{n}__rag_evaluation.sql` | 数据库迁移 |
| `application-evaluation.yml` | 评估专用配置 |

### 零调整

不需要调整任何现有文件。

**结论**：完全零侵入。评估模块通过直接调用现有组件的公共 API 实现，不修改任何生产代码。

---

## 实现计划

### Phase 1: 基础设施（预计 1.5h）

1. 创建 evaluation 包结构
2. EvaluationProperties + EvaluationConfig
3. 数据库迁移脚本
4. Flyway 执行验证

### Phase 2: 数据集管理（预计 2h）

1. DatasetGenerator（LLM 自动生成）
2. DatasetRepository + DatasetExporter
3. 数据集 REST API
4. 导出→人工审核→导入流程验证

### Phase 3: 检索评估（预计 2.5h）

1. RetrievalMetricsCalculator（Recall@K, Precision@K, MRR, NDCG, **Context Precision**）
2. PipelineInstrumenter（含各阶段耗时记录）
3. EvaluationRunner（检索侧）
4. 单元测试（指标计算正确性验证）

### Phase 4: 生成评估（预计 2.5h）

1. LlmJudge + JudgePrompt（含多层 JSON 解析容错 + 重试）
2. FaithfulnessScorer（两步：extractClaims + verifyClaims）
3. ContextRecallScorer（复用两步流程，answer → ground_truth_answer）
4. AnswerRelevanceScorer（embedding cosine 相似度方案）
5. ContextRelevanceScorer（LLM-as-Judge + Few-Shot）
6. EvaluationRunner（完整：检索 + 生成）
7. REST API（运行管理 + 报告查询 + 分页）

### Phase 5: 集成验证（预计 1h）

1. 端到端测试：生成数据集 → 运行评估 → 查看报告
2. 配置切换验证（不同参数的对比运行）
3. Git commit + push

---

## 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| Judge 模型评分不稳定 | 指标波动大 | temperature=0 + 多次运行取均值 |
| LLM 生成数据集质量不够 | ground truth 不准 | 人工审核环节兜底 |
| 评估运行耗时长 | 30条 × 多阶段 × LLM调用 | 串行执行 + 进度上报 |
| 向量库数据不足 | 无法评估 | 确保先上传足够文档 |
| Judge 模型 API 限流 | 评估中断 | 控制并发 + 重试 |
| HybridDocumentRetriever 需要 userId | 评估环境无认证 | 配置固定 test-user-id |
| BailianRerankPostProcessor/MmrDocumentPostProcessor 非 @Component | 无法注入 | 每次 new 创建，从 properties 读参数 |
| 数据集修改导致历史报告失效 | A/B 对比失真 | evaluation_result 增加 item_*_snapshot |
| Answer Relevance embedding 调用成本 | 多次 embedding | 生成问题数限制为 3 |

---

## 不做的事情（Scope 外）

- ❌ 自动参数优化（如 GridSearch、Bayesian Optimization）— 后续迭代
- ❌ 实时评估（线上 A/B 测试）— 后续迭代
- ❌ 多模态评估（图片/表格理解质量）— 等多模态 RAG 就绪后
- ❌ Embedding 模型对比评估 — 独立任务
- ❌ 前端可视化面板 — 后续迭代

---

## 审查修正记录（2026-05-16）

基于 RAGAS 框架对照审查 + 子代理架构审查，修正以下问题：

### P0 修正（6 项）

1. **新增 Context Precision 指标** — 对齐 RAGAS NonLLMContextPrecisionWithReference，衡量检索排序质量
2. **新增 Context Recall 指标** — 对齐 RAGAS Context Recall，衡量标准答案各 claim 是否被 context 覆盖（生成侧指标）
3. **修正 Bean 注入错误** — BailianRerankPostProcessor/MmrDocumentPostProcessor 非 @Component，改为每次 new 创建
4. **新增 item_*_snapshot 字段** — evaluation_result 记录评估时的 ground truth 快照，防止数据修改导致历史报告失效
5. **新增 evaluation_run 索引** — dataset_id 和 status 列索引
6. **Answer Relevance 改用 embedding cosine 相似度** — 对齐 RAGAS，从 answer 反推问题再比 embedding，比 LLM 打分更客观

### P1 修正（5 项）

1. **评分尺度统一** — 所有生成侧指标归一化到 0-1
2. **Judge Prompt 加 Few-Shot 示例** — 提高评分一致性
3. **Judge JSON 解析三层容错 + 重试** — raw → ```json → 正则，最多重试 2 次
4. **Judge 失败不阻塞检索指标** — generation_metrics=null，retrieval_metrics 正常返回
5. **Faithfulness 两步分离** — 先 extractClaims → 再 verifyClaims，避免长回答截断

### 额外改进

- PipelineInstrumenter 增加各阶段耗时记录（stageLatencyMs）
- REST API 增加分页和过滤参数
- retrieved_contents 移除（改为只存 doc ID，按需查询）
- retrieved_doc_ids 改为 TEXT[]（原设计）
- 聚合模式明确为 micro-average（与 RAGAS 一致）
