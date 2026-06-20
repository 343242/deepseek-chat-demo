# Implement（Parent）— 子任务编排 + 集成 gate

> 本 parent 不直接实现，执行以 3 个子任务为准。本文记录依赖、各 PR 范围摘要、集成 gate。技术设计全文见 `design.md`。

## 依赖与顺序

```
PR-A (rag-reorder-core)        ← 基础
   ├── PR-B (rag-fusion-pairwise)   依赖 PR-A 的 rerankTopN
   └── PR-C (rag-parallel-cf)       依赖 PR-A 的顺序调换
```

PR-B 与 PR-C 互相独立，可并行推进。建议顺序：PR-A → (PR-B, PR-C) → 集成。

## PR-A：rag-reorder-core — 顺序调换 + topN 提参（轻量）

1. `RagRetrievalProperties` 加 `rerankTopN`（默认 20）+ compact constructor 校验 `rerankTopN > mmrTopK`；`withOverrides` 透传。
2. `RagConfig.rerankDocumentPostProcessor` 读 properties（删硬编码 10）。
3. `application-dev.yml` 加 `rerank-top-n: 20`。
4. `RagAdvisorFactory.buildPostProcessors`（+ `retrieve()` 两路径）顺序改为 **Rerank → MMR → Parent**（两个独立处理器串行，**不复合**；MMR 在 Rerank 后能取 rerankScore）。
5. 注释同步（MMR 类注释、`resolveRelevanceScore` fallback、`retrieve` 注释）。

**约束**：不并行、不召回扩大（召回仍 30，Rerank 30→20→MMR 10，可工作）。
**A/B**：MMR→Rerank vs Rerank→MMR（串行）。**回滚**：revert commit。

## PR-B：rag-fusion-pairwise — 召回规模（中等）

1. `RagRetrievalProperties` 加 `fusionTopK`（默认 60）+ 校验 `fusionTopK >= rerankTopN`；`withOverrides` 透传（注意 5 参破坏性，优先 builder 或保留旧 3 参委托 —— 架构 review M3）。
2. `HybridSearchService.rrfFusion` L220 `.limit(vectorTopK)` → `.limit(fusionTopK)`（解耦）。先 `impact rrfFusion`。
3. `VectorStoreMapper`：`MAX_PAIRWISE_DOCS` 从硬编码 50 改为可配置 `max(50, fusionTopK)`；**必须改 mapper 常量本身**，不能只在 MMR 层 subList（会被 mapper 50 二次截断 —— 架构 review M4）。先 `impact MAX_PAIRWISE_DOCS`。
4. `application-dev.yml` 加 `fusion-top-k: 60`。

**依赖**：PR-A 的 `rerankTopN=20`。**A/B**：召回 30 vs 60（Rerank 处理 60 条，用户确认 OK）。**回滚**：revert commit。

## PR-C：rag-parallel-cf — CompletableFuture 并行化（复杂，需 design+implement）

1. `RagSearchExecutorConfig` 新增 `ragPostProcessExecutor`（虚拟线程 bean，独立于 `ragSearchExecutor`）。
2. `RerankDocumentPostProcessor` 拆 `rerankOnly(Query, docs)`（保留降级契约）。
3. `MmrDocumentPostProcessor` 拆 `fetchDistanceMatrix(docs)` + `selectByMmr(query, docs, distance)`（**无状态方法**，B3）。
4. 新建 `RerankThenMmrPostProcessor`（CompletableFuture，design §4.1）：rerank `supplyAsync(ragPostProcessExecutor).exceptionally(透传)` ⊥ distance 主线程同步 → `.join()` → `selectByMmr`。
5. `RagAdvisorFactory.buildPostProcessors`：Rerank+MMR 合为复合处理器，list = `[RerankThenMmrPostProcessor, ParentDocumentPostProcessor]`。
6. 单测：rerank 异常透传、distance 异常降级、两者皆失败、blank query（架构 review B2）。

**依赖**：PR-A 的顺序调换。**执行前 gate**：pinning 验证（仅 rerank 分支 HTTP/resilience；distance 在主线程无此风险）。**验证**：延迟对比（串行 vs 并行），distance 等待被吸收。**回滚**：revert（回到 PR-A 串行版）。

## 集成 gate（parent，3 子全合并后）

- [ ] `mvnw test` 全量绿。
- [ ] `detect_changes({scope:"compare", base_ref:"main"})` 仅命中预期符号/流程。
- [ ] 端到端 A/B：完整新链路（Rerank→MMR 并行 + 召回 60）vs 旧链路（MMR→Rerank + 召回 30），nDCG / 命中率 / 延迟不退化。
- [ ] 注释 / 降级契约全链路一致。
- [ ] memory 更新（`rag-remediation-progress` 追加本次改造）。

## 文件改动总览（跨 3 子）

| 文件 | PR | 改动 |
|---|---|---|
| `RagRetrievalProperties.java` | A+B | +`rerankTopN`/`fusionTopK` + 校验 + withOverrides |
| `RagConfig.java` | A | rerank bean 读 properties |
| `application-dev.yml` | A+B | +`rerank-top-n`/`fusion-top-k` |
| `RagAdvisorFactory.java` | A+C | 顺序调换(A) + 复合处理器(C) |
| `HybridSearchService.java` | B | rrfFusion 解耦 |
| `VectorStoreMapper.java` | B | pairwise 阈值联动 |
| `RagSearchExecutorConfig.java` | C | +`ragPostProcessExecutor` bean |
| `RerankDocumentPostProcessor.java` | C | 拆 `rerankOnly` |
| `MmrDocumentPostProcessor.java` | C | 拆 `fetchDistanceMatrix`/`selectByMmr` |
| `RerankThenMmrPostProcessor.java` | C | **新建** |
