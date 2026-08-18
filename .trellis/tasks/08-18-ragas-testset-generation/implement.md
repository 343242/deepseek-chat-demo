# Implement — Ragas 测试集生成 Java 翻译

执行顺序（每步可独立编译验证）：

## Step 1 — graph 包（纯算法，无外部依赖）
- [ ] `graph/Node`、`Relationship`、`RelationshipType`、`KnowledgeGraph`
- [ ] `graph/GraphAlgorithms.findTwoNodesSingleRel`（翻译 ragas graph.py 同名函数）
- [ ] `graph/GraphAlgorithms.findIndirectClusters`（LeidenCommunityDetector 接线 + AdjacencyListGraph DFS 路径采样）
- [ ] 单测：三元组查找、聚类接线（小型已知图）、路径采样

## Step 2 — transforms 包
- [ ] `transforms/PgVectorParser`（解析 `[0.1,0.2,...]` → double[]）+ 单测
- [ ] `transforms/ChunkEntityLoader`（JdbcTemplate：rag_chunk_entity JOIN rag_entity）
- [ ] `transforms/ThemesExtractor`（cheap 模型，THEMES 提示词，JsonExtractorUtil + Jackson）
- [ ] `transforms/EntityOverlapBuilder`（手写 Jaro-Winkler ≥0.9 + Counter 噪声剔除）+ 单测
- [ ] `transforms/VectorCosineBuilder`（double[] 余弦，阈值过滤）+ 单测

## Step 3 — synthesizers 包
- [ ] `Persona`、`QueryStyle`、`QueryLength`、`Scenario`（record/枚举）
- [ ] `TestsetPrompts`（5 个提示词常量 + render；英文原文 + 中文后缀；ragas few-shot 保留）
- [ ] `SingleHopSpecificSynthesizer`（场景生成 + QA 生成）
- [ ] `MultiHopSpecificSynthesizer`（findTwoNodesSingleRel + QA 生成）
- [ ] `MultiHopAbstractSynthesizer`（findIndirectClusters + ConceptCombination + QA 生成）
- [ ] Mockito 罐头 JSON 测试（每合成器至少 1 条成功路径 + 1 条解析失败路径）

## Step 4 — 编排器与替换
- [ ] **先跑 GitNexus impact：DatasetGenerator、DatasetController、DatasetGeneratorStructuredConcurrencyTest**
- [ ] `TestsetGeneratorService`（两段 ScopedTasks：extract → synthesize；去重/过滤；入库）
- [ ] 删除 `DatasetGenerator` 旧实现（单 chunk 路径不保留）
- [ ] `EvaluationProperties` 调整（synthesis-model、personas、cosine-threshold、max-chunks、size；删 questions-per-chunk）
- [ ] `application-evaluation.yml`：~~`app.rag.entity.enabled: true`~~（2026-08-19 开关已删除，实体层无条件装配）+ 新配置项
- [ ] `application.yml` 注释同步（如有交叉引用）

## Step 5 — 异步 API
- [ ] `V29__evaluation_dataset_gen_run.sql`（幂等）
- [ ] `GenerationJobService`（createJob/submitJob/executeJob；evalExecutor + evalRunSemaphore）
- [ ] `GenProgressEvent` + SSE 端点（Sinks per jobId，复用 EvaluationProgressSink 模式）
- [ ] Sweeper 扩展：stale gen_run 清理（并入 EvaluationRunSweeper 定时任务）
- [ ] `DatasetController`：generate 改 202 + jobId；新增状态/事件端点
- [ ] Controller 测试（MockMvc 风格对齐项目现有 controller 测试；若项目无 controller 测试先例，记录说明并以 Service 层测试覆盖）

## Step 6 — 收尾
- [ ] `mvn test` 全绿
- [ ] GitNexus detect_changes 影响面核对
- [ ] trellis-check 子代理审查
- [ ] trellis-update-spec + 批量提交计划（一次性用户确认）

## 验证基准
- 算法行为与 ragas 0.4.3 源码（eval/ragas/src/ragas/testset/）逐函数对照；
- Python 参照实现（eval/generate_testset.py + eval/README.md）作为端到端行为对照；
- 端到端冒烟（可选，需 evaluation profile + 实体重导语料）：生成 ≥1 条多跳样本。

## 完成记录（2026-08-18）

- 全部 Step 完成；实现偏差记录：
  - **Leiden 未接线**：核对 ragas 0.4.3 源码发现 MultiHopAbstract 实际调用 find_n_indirect_clusters（纯 DFS），
    Leiden 版不在任何合成器调用路径 → 按无死代码原则改译 DFS 版（SHA256 派生种子保复现）
  - Jaro-Winkler 手写实现，四组样例与 rapidfuzz 数值完全一致（0.933333/0.866667/0/1.0）
  - LLM 响应解析统一 JsonNode 导航（只取目标字段），免疫 LLM 杂键
- 验证：全量 mvn test 绿（P1 后 1690 个，P2 后 1705 个）；GitNexus impact（LOW，仅 DatasetController 依赖）+
  detect_changes（LOW，零 affected，改动全部位于 evaluation 模块）
