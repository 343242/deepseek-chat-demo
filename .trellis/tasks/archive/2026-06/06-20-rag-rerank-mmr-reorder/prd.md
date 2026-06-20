# RAG 链路调换 Rerank/MMR 顺序 + 并行化

**任务类型**: refactor / performance
**优先级**: P1
**分支**: agentic-rag-dev
**设计文档**: `design.md`（本目录）
**执行计划**: `implement.md`（本目录）

## 背景

当前检索后处理链（`RagAdvisorFactory.buildPostProcessors`）顺序为 **MMR(10) → Rerank(10) → Parent**。

两个矛盾驱动本次改造：

1. **MMR 与设计意图相悖**：`MmrDocumentPostProcessor` 类注释（L16）已写「在 Rerank 精排之后执行」、`resolveRelevanceScore` 优先取 `rerankScore`（L151）——本就按 Rerank→MMR 设计，但编排代码顺序相反。当前 MMR 跑在 Rerank 前，拿不到 rerankScore，只能 fallback 到 rrfScore/0.5，多样性计算的相关性信号偏弱。
2. **调换后 MMR 会失效**：`mmrTopK=10` 且 Rerank `topN=10`（硬编码）。若仅调换顺序为 Rerank(取10)→MMR，MMR 命中 `documents.size() <= topK` 早退分支（L62），彻底变成 no-op。必须同步重设 topN > mmrTopK。

## 目标

将后处理链调整为 **Rerank(topN=20) → MMR(mmrTopK=10) → Parent**，并借顺序调换之机把 MMR 的 DB distance 预取与 Rerank 的 LLM 调用并行化，压缩检索阶段延迟。配套：topN 提参、注释/降级契约同步、虚拟线程 pinning 验证、`EvaluationRunner` A/B 质量验证。

## 任务拆分（parent + 3 子，独立 A/B / 独立回滚）

经架构 review，三个正交改动拆为独立子任务，避免单 PR 耦合多维度、回归难定位：

| 子任务 | 范围 | 依赖 | 独立验证 |
|---|---|---|---|
| **PR-A** `rag-reorder-core` | 顺序调换 Rerank→MMR→Parent（**串行**，不复合）+ rerankTopN 提参(20) + 注释/契约同步 | 无（基础） | A/B：MMR→Rerank(旧) vs Rerank→MMR(新,串行) |
| **PR-B** `rag-fusion-pairwise` | fusionTopK 解耦(60) + rrfFusion 不再复用 vectorTopK + pairwise 阈值联动 max(50,fusionTopK) | PR-A（需 rerankTopN） | A/B：召回30 vs 召回60 |
| **PR-C** `rag-parallel-cf` | 复合处理器 `RerankThenMmrPostProcessor`（CompletableFuture：rerank 异步⊥distance 同步）；新建 `ragPostProcessExecutor` 虚拟线程 bean | PR-A（需顺序调换后的 Rerank→MMR 点） | 延迟对比：串行 vs 并行 |

- PR-A 是基础；PR-B、PR-C 都依赖 PR-A，但彼此独立可并行推进。
- 每个 PR 独立 commit / A/B / 回滚。parent 持跨子集成验收。
- 并行实现用 **CompletableFuture**（非 ScopedTasks）：消除 scope 超时撕穿降级契约（B1）、异常被静默吞（B2）两个陷阱；distance 在调用线程同步跑 → pinning 验证范围从「rerank+distance」缩到「仅 rerank」（B3 无状态约束）。

## 需求

### 功能需求

- **R1 顺序调换**：postProcessor 链由 MMR→Rerank→Parent 改为 **Rerank→MMR→Parent**。两条入口路径（`create()` Advisor 链 + `retrieve()` chat 直检）均同步调整，行为保持一致。
- **R2 topN 提参**：Rerank topN 从 `RagConfig` 硬编码（`new RerankDocumentPostProcessor(reranker, 10)`）提取到 `RagRetrievalProperties`，新增可配置项 `rerank-top-n`，默认 **20**。
- **R3 候选池约束**：硬性保证 `rerankTopN > mmrTopK`（否则 MMR 退化为 no-op）。20 > 10 满足；启动/配置层校验。
- **R4 并行化封装**：新增复合处理器 `RerankThenMmrPostProcessor implements DocumentPostProcessor`，在其 `process()` 内用结构化并发（`ScopedTasks`）并行执行「MMR pairwise distance 预取」与「Rerank API 调用」，Rerank 回来后串行跑 MMR 贪心。
- **R5 注释/契约同步**：修正 `MmrDocumentPostProcessor` 类注释与 `resolveRelevanceScore` fallback 注释（顺序调换后 fallback 分支几乎不再触发）；保留并校验所有降级路径（Rerank 失败透传、distance 失败降级 relevance-only）。

### 扩展需求（hybrid 检索解耦，scope 扩展）

- **R9 fusion-top-k 解耦**：`rrfFusion`（`HybridSearchService` L220）的最终 limit 从复用 `vectorTopK` 解耦为独立 `fusionTopK`（默认 60），实现「召回 60」；`vector-top-k` 回归只管 vectorSearch。校验 `fusionTopK >= rerankTopN`。
- **R10 pairwise 阈值联动（选项 B，必须）**：召回 60 > `MAX_PAIRWISE_DOCS=50` 真实截断，必须把 `pairwiseCosineDistance` 截断阈值联动到 `max(50, fusionTopK)=60`，否则 MMR distance 不完整（top20 落在 51-60 位的文档 key miss → sim=0 误判）。

### 性能需求

- **R6 延迟**：复合处理器的 Rerank 阶段延迟（LLM API）应被 distance 预取（DB IO）重叠，**端到端检索延迟 ≤ 当前顺序调换前的非并行版本**。需埋点/日志对比（或 A/B 取数）。
- **R7 并发（专用虚拟线程 bean）**：新建独立虚拟线程 bean `ragPostProcessExecutor`（资源隔离，不复用 `ragSearchExecutor`），**不开** `spring.threads.virtual.enabled` 全局开关；执行前验证 pinning（HTTP/resilience 包装 + PG JDBC）。

### 质量需求

- **R8 A/B 验证**：用 `EvaluationRunner` 对比「MMR→Rerank（旧）」与「Rerank→MMR（新）」两组配置的召回质量指标（nDCG / 命中率），确认不退化、最好提升。

## 约束

- 不破坏现有降级契约：Rerank API 失败 → 原样透传（`RerankDocumentPostProcessor` L54-59）；MMR distance 失败 → relevance-only 降级（L82-88）。
- **Parent 维持串行末步**：不纳入并行（其输入依赖 Rerank+MMR 存活文档，且是最末步无下游可重叠；证明见 `design.md`）。
- **Spring AI 约束**：`RetrievalAugmentationAdvisor` 是 `final class`、postProcessor 链为框架硬编码顺序 iterator，无法子类化/覆盖 → 并行只能封装进**单个**复合处理器。
- **复用现成基建**：复合处理器复用 `ScopedTasks`（项目结构化并发），但用**新建的专用虚拟线程 bean `ragPostProcessExecutor`**（资源隔离，不复用 `ragSearchExecutor`）；hybrid 检索内部已并行，不动。
- 不引入新的外部依赖。
- CLAUDE.md 硬性要求：改任何符号前先 `impact()`、提交前 `detect_changes()`。
- **扩展项（R9/R10）**：超出纯顺序调换，纳入 hybrid 检索改动（rrfFusion 解耦 + pairwise 阈值联动），需 `impact` `rrfFusion` 与 `VectorStoreMapper`，范围见 design §6.5。

## 验收标准

- [ ] **顺序**：`buildPostProcessors` 与 `retrieve()` 两条路径均为 Rerank→MMR→Parent；Advisor 路径 postProcessor list = `[RerankThenMmrPostProcessor, ParentDocumentPostProcessor]`。
- [ ] **提参**：`rerank-top-n`（默认 20）+ `fusion-top-k`（默认 60）可在 `application.yml` 配置；`RagConfig` 不再硬编码 topN。
- [ ] **召回量**：`fusion-top-k=60` 生效，`rrfFusion` 不再复用 `vector-top-k`；vector-top-k 回归 vectorSearch 本职。
- [ ] **pairwise 完整**：召回 60 条的 pairwise distance 全算（阈值联动 60，无 50 截断），MMR 查 distance 无 key miss。
- [ ] **候选池校验**：`rerankTopN <= mmrTopK` 时启动失败或 log.warn（按 design 决策落地）。
- [ ] **并行**：`RerankThenMmrPostProcessor` 内 Rerank 与 distance 预取并发执行（日志/埋点可见两分支重叠），MMR 贪心在两者就绪后串行跑。
- [ ] **降级保留**：Rerank 失败/distance 失败的降级路径单测覆盖且全绿。
- [ ] **契约同步**：MMR 类注释与 fallback 注释与实际执行顺序一致，无误导性注释。
- [ ] **A/B**：`EvaluationRunner` 跑出新旧两组指标，新方案不退化（记录数据到任务目录）。
- [ ] **回归**：`mvnw test` 全绿；`detect_changes` 仅命中预期符号与执行流。
- [ ] **虚拟线程**：pinning 验证有结论（即使最终不改代码，也要有结论写入 design）。

## 不在本任务范围

- 不改 hybrid 检索内部（已并行）。
- 不改 Parent-Child 替换逻辑本身（仅确认其串行位置不变）。
- 不引入新 Rerank 模型/供应商。
- 虚拟线程全局开关若验证后判定无需开启，则仅留结论不开代码改动（避免 scope creep）。
