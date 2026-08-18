# Design — Ragas 测试集生成 Java 翻译

## 1. 总体结构

```
com.smart.rag.evaluation.testset/
├── graph/
│   ├── Node.java                 // CHUNK 节点：pageContent、metadata、entities、embedding、themes
│   ├── Relationship.java         // (sourceId, targetId, type, property)
│   ├── RelationshipType.java     // 枚举：ENTITY_OVERLAP、SIMILARITY
│   ├── KnowledgeGraph.java       // nodes(Map<id,Node>) + relationships(List)
│   └── GraphAlgorithms.java      // findTwoNodesSingleRel、findIndirectClusters（Leiden 接线）
├── transforms/
│   ├── ChunkEntityLoader.java    // rag_chunk_entity JOIN rag_entity → Map<chunkId, Set<EntityRef>>
│   ├── ThemesExtractor.java      // LLM 主题抽取（cheap 模型，1 调用/chunk）
│   ├── EntityOverlapBuilder.java // 实体重叠边：Jaro-Winkler ≥ 0.9（ragas OverlapScoreBuilder 翻译）
│   └── VectorCosineBuilder.java  // 相似边：现成向量余弦 ≥ threshold（默认 0.7）
├── synthesizers/
│   ├── QueryStyle.java           // 枚举（4 风格）
│   ├── QueryLength.java          // 枚举（3 长度）
│   ├── Scenario.java             // record(nodes, style, length, persona)
│   ├── Persona.java              // record(name, roleDescription)
│   ├── TestsetPrompts.java       // 5 个提示词（text block 常量 + render 方法）
│   ├── SingleHopSpecificSynthesizer.java
│   ├── MultiHopSpecificSynthesizer.java
│   └── MultiHopAbstractSynthesizer.java
├── TestsetGeneratorService.java  // 编排器（替换 DatasetGenerator 内部实现）
└── GenerationJobService.java     // 异步任务生命周期 + SSE 进度
```

## 2. 数据流（对应 ragas generate_with_chunks + 本项目优化）

```
vector_store 采样（id/content/metadata/embedding, ORDER BY RANDOM() LIMIT maxChunks）
  → KnowledgeGraph（CHUNK 节点；embedding 解析为 double[]）
  → ChunkEntityLoader 装实体（单一来源，无 LLM）
  → ThemesExtractor（cheap 模型，ScopedTasks 并发，COLLECT_ALL）
  → EntityOverlapBuilder + VectorCosineBuilder（本地计算，无 LLM）
  → 合成器可用性：
      实体边空 → 跳过 MultiHopSpecific
      相似边空 → 跳过 MultiHopAbstract
  → 场景生成（ThemesPersonasMatching，cheap 模型）
  → 样本合成（QA 生成，synthesis 模型）→ 去重 + 过滤空条目
  → DatasetRepository.insertItems 入库
```

## 3. ragas → Java 映射（逐文件）

| ragas 源文件 | Java 类 | 翻译要点 |
|---|---|---|
| `testset/graph.py` Node/Relationship/KnowledgeGraph | graph 包 | pydantic → record/不可变类；省去 save/load JSON（入库走 DatasetRepository） |
| `graph.py::find_two_nodes_single_rel` | GraphAlgorithms.findTwoNodesSingleRel | 实体重叠三元组直接翻译（纯 Python，无三方依赖） |
| `graph.py::find_indirect_clusters` | GraphAlgorithms.findIndirectClusters | Leiden 用项目 `LeidenCommunityDetector`；路径采样在 `AdjacencyListGraph` 上 DFS（对应 networkx all_simple_paths 截断版） |
| `transforms/extractors/llm_based.py` ThemesAndConceptsExtractorPrompt | TestsetPrompts.THEMES | 英文原文 + 中文输出后缀；StringIO 输出改为 JSON `{"themes": [...]}` |
| `transforms/relationship_builders/traditional.py` OverlapScoreBuilder | EntityOverlapBuilder | rapidfuzz Jaro-Winkler → 手写 Jaro-Winkler（~60 行，项目偏好 JDK/自写 > 三方库）；阈值 0.9、Counter 噪声剔除照搬 |
| `transforms/relationship_builders/cosine.py` CosineSimilarityBuilder | VectorCosineBuilder | numpy 分块矩阵 → double[] 循环；输入为 DB 现成向量 |
| `synthesizers/prompts.py` ThemesPersonasMatchingPrompt | TestsetPrompts.PERSONA_MATCH | 输入输出模型改 Jackson record |
| `synthesizers/single_hop/{base,specific,prompts}` | SingleHopSpecificSynthesizer | 场景采样 + QA 生成合并翻译 |
| `synthesizers/multi_hop/{base,specific,abstract,prompts}` | MultiHop 两个合成器 | prepare_combinations / 多样性采样 / make_contexts（`<1-hop>` 标签）照搬 |
| `synthesizers/utils.py` calculate_split_values | TestsetGeneratorService 内私有方法 | ceil 分配 |
| `executor.py` + `RunConfig` | ScopedTasks（COLLECT_ALL + maxConcurrency + defaultTimeout） | NaN 占位 bug 不翻译——失败子任务返回空结果（借鉴 Python 侧 _tolerant 补丁的语义） |
| `persona.py` | Persona record + 配置列表 | 不移植 LLM persona 生成 |
| `llms/`、`embeddings/`、`prompt/` 框架 | RewriteClientResolver + JsonExtractorUtil + Jackson | 项目 LLM SPI 原生路径 |

**不翻译**：NERPrompt（实体走 DB）、SummaryExtractor/CustomNodeFilter/HeadlineSplitter（预切块路径无此环节）、
KeyphrasesExtractor/TitleExtractor/TopicDescription（不在预切块默认管线）、persona 生成 prompt。

## 4. 关键实现细节

### 4.1 pgvector 文本解析
`vector_store.embedding` 列（`vector(1536)`）经 JdbcTemplate 读取为 `[0.123,0.456,...]` 字符串。
`PgVectorParser.parse(String) -> double[]` 工具类 + 单测（空串、null、维度不足、异常格式）。

### 4.2 Jaro-Winkler
手写实现（JDK 无内置；引三方库与项目"JDK 优先"偏好冲突）。阈值 0.9 与 ragas 一致，
用于实体名模糊匹配（`name_norm` 已规范化，模糊匹配仍需处理中英文别名）。

### 4.3 提示词形态
`TestsetPrompts` 常量类：ragas 英文 instruction 原文 + 统一中文后缀
（"生成的问题和答案必须使用简体中文；问题贴近真实中文用户提问口吻"）+ few-shot 示例（ragas 原例）。
LLM 调用统一：`chatClient.prompt().user(rendered).call().content()` → `JsonExtractorUtil.extractJson` → Jackson。

### 4.4 并发与超时
两段 ScopedTasks（镜像 Python 的两阶段）：
- `"testset-extract"`：Themes 抽取，maxConcurrency = runner.concurrency，timeout = item-timeout-seconds；
- `"testset-synthesize"`：场景 + 样本生成，同上。
阶段内单条失败 → 返回空贡献（COLLECT_ALL + 子任务内部 try-catch，同现有 DatasetGenerator 模式）；
阶段进度经 GenerationJobService 发 SSE。

### 4.5 异步任务（V29）

```sql
CREATE TABLE evaluation_dataset_gen_run (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    user_id       BIGINT NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'pending',
    config        JSONB,
    progress      JSONB,
    dataset_id    BIGINT REFERENCES evaluation_dataset(id),
    error         TEXT,
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- CHECK 约束同 V12 风格；idx on status
```

- `GenerationJobService`：createJob（插入 pending）→ submitJob（evalExecutor + evalRunSemaphore.tryAcquire，
  超时→FAILED，镜像 EvaluationExecutionService）→ executeJob（调 TestsetGeneratorService，阶段回调发进度）
- 进度事件：`GenProgressEvent` record（phase, current, total, message）；
  Sinks 复用 `EvaluationProgressSink` 的模式（per-jobId map + replay limit）
- Sweeper：`EvaluationRunSweeper` 增加对 gen_run 表的 stale 清理，或独立小 sweeper（实现时取其一，避免重复代码倾向后者并入现有 sweeper 的定时任务）

### 4.6 API 变更

| 端点 | 变更 |
|---|---|
| `POST /api/evaluation/datasets/generate` | body 不变（name/userId），响应改 202 `{jobId}` |
| `GET /api/evaluation/datasets/generate/{jobId}` | 新增：任务状态查询 |
| `GET /api/evaluation/datasets/generate/{jobId}/events` | 新增：SSE 进度 |

## 5. 配置

```yaml
app:
  rag:
    entity:
      enabled: true          # 评估 profile 前置条件（定义变更）
  evaluation:
    generation-model: deepseek-v4-flash     # 抽取（themes、persona 匹配）
    dataset:
      synthesis-model: qwen3.8-max          # 出题
      sample-size: 50                        # 语义变更：目标条数（原 chunk 采样数）
      max-chunks: 200
      questions-per-chunk: 2                 # 删除（ragas 模式无此概念）
      cosine-threshold: 0.7
      personas:                              # 固定中文 persona
        - name: 企业新员工
          role_description: ...
```

EvaluationProperties 相应调整；`sampleSize` 语义变化在 javadoc 标注。

## 6. 风险

| 风险 | 缓解 |
|---|---|
| chunk 向量 vs ragas 摘要向量阈值分布不同 | cosine-threshold 配置化；首跑对照 Python 产出校准 |
| LeidenCommunityDetector 输入是 WeightedGraph | 适配层：KG 关系 → AdjacencyListGraph；单测覆盖接线 |
| deepseek 思考模型 content 解析 | 项目 SPI 已处理（Python 侧验证 content 干净） |
| DB 实体数据缺失（环境未开实体层） | 评估 profile 强制 entity.enabled=true；缺失时多跳自然降级为单跳（ragas 原生行为） |
