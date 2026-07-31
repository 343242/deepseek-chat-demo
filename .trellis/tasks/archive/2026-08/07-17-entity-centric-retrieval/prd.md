# Entity-Centric Retrieval Enhancement

## Goal

将多跳推理能力从 Agent 编排层下沉到检索层：在现有 hybridSearch（vector + BM25 + RRF + rerank）基础上引入实体中心索引层（SAG event-entity 超边 + UnWeaver 实体等价类聚合），并以 `weak_tie_score`(P0) + `bridge_score`(P1) 融合排序修复 SAG 的频次剪枝盲区。本阶段**不暴露 Agent 工具**，实体检索仅作为 hybridSearch 的并行召回路径（Path C）。

## Authoritative Design

技术设计的唯一来源是 `docs/design/entity-centric-retrieval.md`（1605 行，已通过 2 轮审计，状态行见该文档 §0）。本任务及所有子任务的 `design.md` 均为指向该主文档的薄指针 + 子任务级补充，不复制设计正文，避免漂移。所有章节引用（§x.y）均指该主文档。

## Background / Confirmed Facts

- 现状：smart-rag 是 Agentic VectorRAG（DeepRAG + Self-RAG + query rewrite + rerank），多跳靠 Agent 循环迭代，每跳一次 LLM 调用。
- 代码库核验（2026-07）：根包 `com.smart.rag`；`rag/` 子包已存在（`rag/config/RagRetrievalProperties`、`rag/etl/FastTrackStrategy`、`rag/event/EtlCompletedEvent`）。下一个迁移版本 V21（当前最高 V20=`V20__trace_event.sql`）。`HybridSearchService` 位于 `agent/service/`。`evaluation/runner` + `evaluation/metrics/retrieval` 已存在。基线分支 `agentic-rag-dev`。
- 侵入面（主文档 §10.3 审计后）：仅 1 处非侵入（FastTrackStrategy 新增事件发布）+ 1 处中等（DocumentSupersedeService 经事件解耦清理）。`HybridSearchService` 与 `RagRetrievalProperties` 经 OCP/ISP 重构后分别为零改动 / 仅加 1 字段。

## Child Scope Contract（跨子任务边界，权威来源）

每个子任务承担主文档中明确的类/文件，**不得越界**。重叠 = bug。

| 子任务 | 主文档章节 | 拥有的产物 | 不拥有 |
|---|---|---|---|
| `ecr-db-migration` | §3, §13 | `db/migration/V21__entity_centric_index.sql`：4 表（rag_entity/rag_chunk_entity/rag_event/rag_entity_cooccurrence）+ `v_entity_neighbors` 视图 + 全部索引（含 `COALESCE`/`LEAST/GREATEST` 表达式唯一索引、HNSW `vector_cosine_ops`） | 任何 Java/mapper |
| `ecr-graph-algorithm` | §5.2 ①②③ | `infrastructure/algorithm/graph/`：`WeightedGraph` 接口 + `AdjacencyListGraph` + `LeidenCommunityDetector` + 单测 | 任何业务概念、DB/mapper、CooccurrenceGraphLoader/CommunityDetectionJob |
| `ecr-retrieval-path-abstraction` | §6.5 | `RetrievalPath` 接口 + `ScoredDocument` record + `VectorRetrievalPath`/`Bm25RetrievalPath` 适配器 + `HybridSearchService` 重构为依赖 `List<RetrievalPath>` | EntityRetrievalPath（属 path-c-retrieval）、任何实体代码 |
| `ecr-extraction-pipeline` | §4, §8.2, §8.4 | `EntityExtractionService`/`EntityCanonicalizationService`/`EntityEmbeddingService` + `EntityMapper`/`EventMapper`/`ChunkEntityMapper` + `EtlVectorizedEvent` + FastTrackStrategy 事件发布钩子 + `EntityIndexCleanupService` + DocumentSupersedeService 清理集成 + 抽取 prompt(§4.2) | 结构分（structure-scores）、在线检索（path-c）、图算法 |
| `ecr-structure-scores` | §5.1, §5.2 ④⑤, §5.4 | `EntityCooccurrenceMapper` + `CooccurrenceGraphLoader` + `CommunityDetectionJob` + `EntityIndexService`（weak_tie SQL + bridge SQL + 共现投影 SQL） | 图算法内部（graph-algorithm）、抽取（extraction-pipeline） |
| `ecr-path-c-retrieval` | §6.1-6.4, §7.1, §7.2, §9.1 | `EntitySeedExtractor`/`EntityFrontierRanker`/`EntityVoteRetriever`/`EntityExpansionRetriever`/`EntityRetrievalPath` + `RagRetrievalProperties.EntityRetrievalProperties` 嵌套 record + `application.yml` entity.* + 融合/投票/扩展 SQL + trace | 结构分计算（structure-scores，仅读取其列）、HybridSearchService 重构（retrieval-path-abstraction） |

## Dependency Graph（子任务排序，写入各子任务 prd.md）

```
ecr-db-migration ──┬─→ ecr-extraction-pipeline ──→ ecr-structure-scores ──┐
                   │                                            ↑          ↓
ecr-graph-algorithm ────────────────────────────────→ ecr-structure-scores
                                                                    │
ecr-retrieval-path-abstraction ──→ ecr-path-c-retrieval ───────────┘
```

- **无依赖、可并行启动**：`ecr-db-migration`、`ecr-graph-algorithm`、`ecr-retrieval-path-abstraction`（三者互不依赖）。
- `ecr-extraction-pipeline` 依赖 `ecr-db-migration`（表必须存在）。
- `ecr-structure-scores` 依赖 `ecr-db-migration` + `ecr-graph-algorithm`（Leiden）+ `ecr-extraction-pipeline`（需实体数据）。
- `ecr-path-c-retrieval` 依赖 `ecr-db-migration` + `ecr-retrieval-path-abstraction` + `ecr-extraction-pipeline`（mapper/数据）；**读取**（不计算）structure-scores 的列。

## Requirements（父级，跨子任务集成层）

- R1：`entity.enabled=false`（默认）时，hybridSearch 行为与现状**完全等价**——Path A/B 不变，Path C 不注册，零延迟回归。
- R2：`entity.enabled=true` 时，Path C 作为第三路并行召回，与 A/B 经统一 RRF 融合后进现有 rerank + MMR。
- R3：安全隔离双轨——entity 层 `e.user_id = :userId`（BIGINT）+ chunk 回链 `vs.metadata->>'userId' = :userIdStr`（字符串），两种绑定同时传入且一致（主文档 §10.1）。共现图严格按 user/team 隔离（§3.2）。
- R4：文档删除/supersede 时实体索引级联清理无孤儿（§8.4）。
- R5：离线结构分（weak_tie/bridge）为缓存属性，在线只读不计算；未计算时用默认值兜底不阻塞查询（§5.3）。
- R6：四大设计原则贯彻——SRP（每类单一职责，见 §4.4/§5.2/§6.1 拆分）、OCP（RetrievalPath 注册，§6.5）、DIP（依赖 ChatCapable/WeightedGraph 抽象）、CARP（图算法下沉 infrastructure/）。各子任务 design.md 须映射自身职责到这些原则。

## Acceptance Criteria（父级集成验收，子任务完成后由父任务复核）

- [ ] AC1：端到端——ingest 一篇多主题文档 → 实体/event 落库 → 结构分计算 → 多跳 query 命中 Path C 并返回正确 chunk，trace(§9.1) 完整输出。
- [ ] AC3：`entity.enabled=false` 时现有 hybridSearch 全部既有测试绿色，零行为回归。
- [ ] AC4：删除文档后 `rag_entity`/`rag_event`/`rag_chunk_entity` 无残留指向已删 chunk_id 的行；`degree=0` 的孤儿实体被清除。
- [ ] AC5：Path C 端到端延迟 P99 < 800ms（主文档 §9.2 告警阈值）；合并 LLM 调用后 < 200ms（§11.4 缓解）。
- [ ] AC6：跨用户隔离测试——user A 的实体/共现图/query 结果不包含 user B 的任何数据。

## Out of Scope

- Agent 工具暴露（`entitySearch` 工具，§10.4 后续阶段）。
- Level 2/3 规范化（别名词典、embedding 合并，§4.3 后续迭代）。
- H=2+ 多跳扩展（§11.5 后续）。
- 知识图谱数据库引入（§1.3 明确不引入）。
- 消融评估（§12）——不在本任务范围，后续独立实施。
- 灰度/用户级路由策略——采用全局开关 `entity.enabled`，不做分步灰度。

## Open Questions

- OQ1（已决）：全局开关（`app.rag.entity.enabled`），不分步骤、不做用户级灰度路由。`@ConditionalOnProperty` 机制不变，与现有 rerank/evaluation/mcp 功能开关惯例一致。
- OQ2（子任务开放问题汇总，已分派到具体子任务 prd.md）：LLM seed extraction 与 query rewrite 合并优化（§11.4，消除 ~300ms）→ path-c OQ1，本阶段独立调用，性能基线建立后作为首优；`RetrievalPath`/`EntityRetrievalPath` 包位置（agent/service vs rag/retrieval）→ retrieval-path-abstraction 决定，path-c OQ2 跟随；fastutil 8.5.18 依赖（pom.xml 当前无）→ graph-algorithm implement.md 负责添加；DashScope batch embedding 批次上限 → extraction-pipeline OQ2，默认 batchSize=10。
- OQ3（跨子任务测试前置，父 implement.md Wave 2 gate）：`ecr-structure-scores` 的 `CommunityDetectionJob` 端到端测试依赖 `ecr-extraction-pipeline` 先实现 `EntityMapper.batchUpdateCommunities/updateBridgeScores/clearStaleFlag` 三个写回方法。集成测试须在 extraction-pipeline 落地这些方法后运行；structure-scores 的单元测试可先用 stub mapper 解耦。
