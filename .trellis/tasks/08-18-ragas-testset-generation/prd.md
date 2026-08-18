# Ragas 测试集生成 Java 翻译（KG + 合成器 + 异步 API）

## Goal

以 `eval/ragas/`（ragas v0.4.3+8，冻结参考实现）为蓝本，将知识图谱式多跳测试集生成翻译为 Java，
整合进 `com.smart.rag.evaluation` 模块，**直接替换**现有 `DatasetGenerator` 的单 chunk 生成逻辑（不留双轨）。

## Background

- 现有 `DatasetGenerator` 只能从单个 chunk 出题（单跳、题型单一）；
- ragas 的测试集生成管线（KG → transforms → synthesizers）能产出单跳 + 多跳 + 抽象综合题；
- Python 参照实现已在 `eval/generate_testset.py` 跑通并产出过合格样本（中文 persona、中文输出约束已验证）；
- 本任务是其 Java 翻译，Java 侧利用项目已有基础设施（ScopedTasks、RewriteClientResolver、Leiden、实体中心索引层）。

## Requirements

1. **新包 `com.smart.rag.evaluation.testset`**，含 `graph/`、`transforms/`、`synthesizers/` 三个子包与编排器；
2. **实体单一来源**：KG 实体只从 `rag_chunk_entity` JOIN `rag_entity`（name_norm/type）读取；
   **不移植 LLM NER**（ragas NERPrompt 不翻译）；chunk 无实体行 → 不参与实体边（数据驱动自然降级，非新增分支）；
3. **定义变更**：`application-evaluation.yml` 增加 `app.rag.entity.enabled: true`（评估 profile 前置条件）；
   开发阶段语料无实体数据靠重新导入解决，不写 backfill、不写兜底；
4. **主题相似边**：复用 `vector_store` 现成 embedding 列（pgvector 文本格式解析），零 embedding 调用；
5. **Leiden 复用**：`findIndirectClusters` 用项目 `infrastructure/algorithm/graph/LeidenCommunityDetector`
   + `AdjacencyListGraph`，照 ragas `graph.py::find_indirect_clusters` 原算法（聚类 + 路径采样），不用降级 DFS 版；
6. **合成器三个**：SingleHopSpecific / MultiHopSpecific / MultiHopAbstract，含 5 个提示词
   （Themes、ThemesPersonasMatching、single-hop QA、multi-hop QA、ConceptCombination），
   ragas 英文原文 + 中文输出约束后缀；persona 用配置注入的固定中文列表（默认 4 个），不移植 LLM persona 生成；
7. **替换 DatasetGenerator**：删除旧的单 chunk 生成路径，`POST /api/evaluation/datasets/generate` 语义升级；
8. **异步 API**：V29 Flyway 迁移建 `evaluation_dataset_gen_run` 表（风格同 V11、CHECK 同 V12）；
   generate 端点改 202 + jobId；新增 `GET /api/evaluation/datasets/generate/{jobId}/events` SSE 进度；
   复用 `evalExecutor`（虚拟线程）+ `evalRunSemaphore` 背压模式；
9. **配置**（EvaluationProperties / application-evaluation.yml）：`dataset.synthesis-model`（出题主模型）、
   现有 `generation-model` 转任抽取模型、`dataset.personas`、`dataset.cosine-threshold`（默认 0.7）、
   `dataset.max-chunks`、`dataset.size`；**无 entity-source 开关**；
10. **产物入库**：经现有 `DatasetRepository.insertItems` 写 `evaluation_dataset_item`（字段 1:1 映射，无表结构变更）。

## Non-goals

- 指标层对齐（见子任务 ragas-metrics-alignment）；
- LLM NER、LLM persona 生成、文档标题切分（预切块输入不需要）；
- 存量数据兼容与 backfill（上线前无存量用户，改定义不改兼容）；
- 前端改动（评估页是占位符）。

## Acceptance Criteria

- [ ] 旧 `DatasetGenerator` 单 chunk 生成路径删除，`evaluation` 包内无残留死代码；
- [ ] 三个合成器 + 图算法（findTwoNodesSingleRel / findIndirectClusters / 余弦 / Jaro-Winkler 重叠）有单元测试且全绿；
- [ ] pgvector 文本格式解析工具类有单测；
- [ ] LLM 依赖类按项目模式测试（Mockito 罐头 JSON），ScopedTasks 用法有断言（RecordingScopedTasks 风格）;
- [ ] `mvn test` 全绿；
- [ ] `application-evaluation.yml` 含 `app.rag.entity.enabled: true` 与全部新配置项；
- [ ] V29 迁移可执行（幂等），表结构经 Flyway 管理；
- [ ] 改动 `DatasetGenerator` / `DatasetController` 前跑过 GitNexus impact；提交前跑 detect_changes；
- [ ] 遵守 llm-spi.md（RewriteClientResolver 入口、无 ChatModel/ChatClient.Builder 注入、无效候选 fail-fast）。

## Definition of Done

- 测试全绿或明确记录环境阻塞点；
- spec 影响已评估（trellis-update-spec）；
- 按项目偏好提交（Conventional Commits，批量提交一次性确认）。
