# RAG 评估系统设计文档

**日期**: 2026-05-16
**分支**: rag-dev
**状态**: 草案

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

1. **检索侧评估**：量化 Recall@K、Precision@K、MRR、NDCG
2. **生成侧评估**：量化 Faithfulness（忠实度）、Answer Relevance（回答相关性）、Context Relevance（上下文相关性）
3. **Pipeline 阶段对比**：对比 Rerank 前后、MMR 前后、ParentChild 前后的指标变化
4. **参数调优支撑**：同一测试集、不同参数配置的 A/B 对比
5. **趋势追踪**：历次评估结果可追溯，评估代码变更的影响

## 设计原则

### P1: 零侵入现有 Pipeline

- 评估模块是独立的 Spring `@Component`，不修改任何现有 RAG 代码
- 通过直接调用现有 Bean（HybridDocumentRetriever、BailianRerankPostProcessor 等）组装评估流程
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
│   │   └── RetrievalMetricsCalculator.java // 检索指标聚合
│   └── generation/
│       ├── FaithfulnessScorer.java        // 忠实度（LLM-as-Judge）
│       ├── AnswerRelevanceScorer.java     // 回答相关性
│       ├── ContextRelevanceScorer.java    // 上下文相关性
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
| relevant_chunk_ids | TEXT[] | 相关 chunk 的 ID 列表（检索 ground truth） |
| relevant_content | TEXT | 相关文档内容（用于 context relevance） |
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
| query_rewritten | TEXT | 改写后的查询 |
| retrieved_doc_ids | TEXT[] | 检索到的文档 ID |
| retrieved_contents | TEXT[] | 检索到的文档内容 |
| generated_answer | TEXT | LLM 生成的回答 |
| stage_snapshots | JSONB | 各阶段快照 |
| retrieval_metrics | JSONB | 检索指标（recall, precision, mrr, ndcg） |
| generation_metrics | JSONB | 生成指标（faithfulness, answer_relevance, context_relevance） |
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

    // 注入现有 Pipeline 组件（零侵入：不修改这些类的任何代码）
    private final QueryNormalizer queryNormalizer;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final BailianRerankPostProcessor reranker;
    private final MmrDocumentPostProcessor mmrProcessor;
    private final ParentDocumentPostProcessor parentProcessor;
    private final ChatClient.Builder chatClientBuilder;
    private final RagRetrievalProperties properties;
    private final PipelineInstrumenter instrumenter;
    private final LlmJudge judge;

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

        // 4. Rerank 阶段（可选）
        List<Document> afterRerank = config.isRerankEnabled()
            ? reranker.process(query, retrieved) : retrieved;
        inst.capture("after_rerank", afterRerank);

        // 5. MMR 阶段（可选）
        List<Document> afterMmr = config.isMmrEnabled()
            ? mmrProcessor.process(query, afterRerank) : afterRerank;
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
            new ObjectMapper()
        );
    }
}
```

**关键设计**：
- `HybridDocumentRetriever` 是非 Spring 管理的 POJO（无 `@Component`），通过构造器注入依赖，评估时直接 `new` 即可
- 各阶段捕获完整快照（文档列表 + 时间戳），用于阶段对比
- `EvalConfig` 支持覆盖任意参数，同一数据集可跑不同配置的 A/B 对比

### 2. Pipeline 阶段插桩（PipelineInstrumenter）

```java
public class PipelineInstrumenter {

    private final List<StageSnapshot> snapshots = new ArrayList<>();

    public void capture(String stageName, Object data) {
        snapshots.add(new StageSnapshot(
            stageName,
            serialize(data),
            System.currentTimeMillis()
        ));
    }

    public List<StageSnapshot> getSnapshots() {
        return List.copyOf(snapshots);
    }

    /**
     * 从快照中提取指定阶段的文档列表
     * 用于计算每个阶段的检索指标（阶段对比）
     */
    @SuppressWarnings("unchecked")
    public List<Document> getDocumentsAtStage(String stageName) {
        return snapshots.stream()
            .filter(s -> s.stageName().equals(stageName))
            .findFirst()
            .map(s -> (List<Document>) deserialize(s.data()))
            .orElse(List.of());
    }

    public List<String> getDocIdsAtStage(String stageName) {
        return getDocumentsAtStage(stageName).stream()
            .map(Document::getId)
            .toList();
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

**实现策略**：
- 相关性分级：ground truth 中的文档 rel=1，其余 rel=0（简化版）
- 未来可扩展为 rel=2（高度相关）/ rel=1（相关）/ rel=0（不相关）

```java
public class RetrievalMetricsCalculator {

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

        return new RetrievalMetrics(recall, precision, mrr, ndcg);
    }

    private double computeMRR(List<String> retrieved, Set<String> relevant) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (relevant.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private double computeNDCG(List<String> retrieved, Set<String> relevant, int k) {
        // DCG
        double dcg = 0;
        int limit = Math.min(k, retrieved.size());
        for (int i = 0; i < limit; i++) {
            if (relevant.contains(retrieved.get(i))) {
                dcg += 1.0 / log2(i + 2); // rank从1开始 → i+2
            }
        }
        // IDCG: 所有相关文档排在最前
        double idcg = 0;
        int idealLimit = Math.min(k, relevant.size());
        for (int i = 0; i < idealLimit; i++) {
            idcg += 1.0 / log2(i + 2);
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    private double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
```

### 4. 生成侧指标（LLM-as-Judge）

#### Faithfulness（忠实度）

**衡量**：生成的回答是否仅基于检索到的上下文，没有"幻觉"。

**方法**：
1. 将 answer 拆分为独立声明（claims）
2. 对每个 claim，判断是否可从 context 中推导出来
3. Faithfulness = 可推导的 claims 数 / 总 claims 数

**Judge Prompt**：
```
给定以下上下文（检索到的文档片段）和生成的回答。

上下文：
{context}

回答：
{answer}

请判断回答中的每个声明是否可以从上下文中推导出来。
一个声明是"可推导的"指：上下文中包含足够的信息来支持该声明。

按以下 JSON 格式输出（不要输出其他内容）：
{
  "claims": [
    {"claim": "声明内容", "supported": true, "reason": "上下文第X段提到..."},
    {"claim": "声明内容", "supported": false, "reason": "上下文中未提及..."}
  ],
  "faithfulness_score": 0.85
}
```

#### Answer Relevance（回答相关性）

**衡量**：回答是否直接回应了用户的问题。

**Judge Prompt**：
```
给定以下用户问题和系统生成的回答。

问题：{question}
回答：{answer}

请评估回答是否直接回应了用户的问题。
评分标准：
- 5分：完全回应，无多余信息
- 4分：主要回应，少量冗余
- 3分：部分回应，有遗漏或偏题
- 2分：大部分未回应
- 1分：完全无关

输出 JSON（不要输出其他内容）：
{
  "relevance_score": 4,
  "reason": "回答涵盖了问题的主要方面，但未提及..."
}
```

#### Context Relevance（上下文相关性）

**衡量**：检索到的上下文对回答问题的有用程度。

**Judge Prompt**：
```
给定以下用户问题和检索到的文档片段。

问题：{question}

文档片段：
{context_chunks}

请评估每个文档片段对回答问题的有用程度。
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
  "overall_relevance": 3.5,
  "useful_chunk_ratio": 0.6
}
```

### 5. LLM Judge 实现

```java
@Component
@Profile("evaluation")
public class LlmJudgeImpl implements LlmJudge {

    private final ChatClient judgeClient;
    private final String judgeModel;

    public LlmJudgeImpl(ChatClient.Builder builder, EvaluationProperties props) {
        this.judgeModel = props.getJudgeModel();
        // Judge 使用独立模型，temperature=0 确保确定性
        this.judgeClient = builder.build();
    }

    @Override
    public JudgeVerdict evaluate(String prompt) {
        String response = judgeClient.prompt()
            .user(prompt)
            .options(ChatOptionsBuilder.builder()
                .withModel(judgeModel)
                .withTemperature(0.0)
                .build())
            .call()
            .content();
        return parseVerdict(response);
    }

    private JudgeVerdict parseVerdict(String json) {
        // 从 LLM 返回中提取 JSON 部分
        String cleaned = extractJson(json);
        try {
            return objectMapper.readValue(cleaned, JudgeVerdict.class);
        } catch (Exception e) {
            log.warn("Failed to parse judge verdict: {}", e.getMessage());
            return JudgeVerdict.failed(e.getMessage());
        }
    }
}
```

**关键配置**：
- temperature=0：Judge 需要确定性输出，减少评分波动
- Judge 模型通过配置指定：`app.evaluation.judge-model`
- JSON 解析容错：LLM 输出可能带 markdown 包裹，需 extractJson 清理

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
    relevant_chunk_ids  TEXT[],
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

-- 评估结果
CREATE TABLE evaluation_result (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              BIGINT NOT NULL REFERENCES evaluation_run(id) ON DELETE CASCADE,
    item_id             BIGINT NOT NULL REFERENCES evaluation_dataset_item(id),
    query_rewritten     TEXT,
    retrieved_doc_ids   TEXT[],
    retrieved_contents  TEXT[],
    generated_answer    TEXT,
    stage_snapshots     JSONB,
    retrieval_metrics   JSONB,
    generation_metrics  JSONB,
    error               TEXT,
    latency_ms          INT
);
CREATE INDEX idx_eval_result_run ON evaluation_result(run_id);
```

---

## REST API

### 数据集管理

```
POST   /api/evaluation/datasets/generate     # LLM 自动生成数据集
GET    /api/evaluation/datasets               # 列出数据集
GET    /api/evaluation/datasets/{id}          # 数据集详情
GET    /api/evaluation/datasets/{id}/export   # 导出为 JSON（人工审核）
POST   /api/evaluation/datasets/{id}/import   # 导入审核后的 JSON
PUT    /api/evaluation/datasets/{id}/items/{itemId}  # 更新单条数据项（审核修正）
```

### 评估运行

```
POST   /api/evaluation/runs                   # 启动评估运行
GET    /api/evaluation/runs                   # 列出运行
GET    /api/evaluation/runs/{id}              # 运行详情 + 聚合指标
GET    /api/evaluation/runs/{id}/results      # 逐条结果
GET    /api/evaluation/runs/compare?ids=1,2,3 # 多次运行对比
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
      "ndcg@10": 0.73
    },
    "generation": {
      "faithfulness": 0.82,
      "answerRelevance": 4.2,
      "contextRelevance": 3.8
    },
    "stageComparison": {
      "afterRetrieval":    { "recall@10": 0.85, "ndcg@10": 0.70 },
      "afterRerank":       { "recall@10": 0.85, "ndcg@10": 0.78 },
      "afterMmr":          { "recall@10": 0.82, "ndcg@10": 0.76 },
      "afterParentChild":  { "recall@10": 0.82, "ndcg@10": 0.76 }
    }
  }
}
```

### 多次运行对比

```json
{
  "runs": [
    { "name": "baseline", "recall@10": 0.85, "ndcg@10": 0.73, "faithfulness": 0.82 },
    { "name": "no-rerank", "recall@10": 0.85, "ndcg@10": 0.64, "faithfulness": 0.79 },
    { "name": "topK-20", "recall@10": 0.92, "ndcg@10": 0.71, "faithfulness": 0.80 }
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
| HybridDocumentRetriever | 评估直接调用 retrieve()，或 new 新实例 |
| BailianRerankPostProcessor | 评估直接调用 process() |
| MmrDocumentPostProcessor | 评估直接调用 process() |
| ParentDocumentPostProcessor | 评估直接调用 process() |
| QueryNormalizer | 评估直接调用 normalize() |
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

### Phase 3: 检索评估（预计 2h）

1. RetrievalMetricsCalculator（Recall@K, Precision@K, MRR, NDCG）
2. PipelineInstrumenter
3. EvaluationRunner（检索侧）
4. 单元测试（指标计算正确性验证）

### Phase 4: 生成评估（预计 2h）

1. LlmJudge + JudgePrompt
2. FaithfulnessScorer / AnswerRelevanceScorer / ContextRelevanceScorer
3. EvaluationRunner（完整：检索 + 生成）
4. REST API（运行管理 + 报告查询）

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

---

## 不做的事情（Scope 外）

- ❌ 自动参数优化（如 GridSearch、Bayesian Optimization）— 后续迭代
- ❌ 实时评估（线上 A/B 测试）— 后续迭代
- ❌ 多模态评估（图片/表格理解质量）— 等多模态 RAG 就绪后
- ❌ Embedding 模型对比评估 — 独立任务
- ❌ 前端可视化面板 — 后续迭代
