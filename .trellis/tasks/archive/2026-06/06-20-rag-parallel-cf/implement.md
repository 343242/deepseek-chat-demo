# Implement — PR-C CompletableFuture 并行化

## Phase 0 — 执行前 gate

- [ ] `impact`: `RerankDocumentPostProcessor`、`MmrDocumentPostProcessor`、`RagAdvisorFactory.buildPostProcessors`、`RagSearchExecutorConfig`。
- [ ] pinning 验证（**仅 rerank 分支**）：`AbstractRerankClient`/`ResilientRerankClient` 的 HTTP + resilience 是否 synchronized 阻塞。必要时 `-Djdk.tracePinnedThreads=full`。

## Phase 1 — executor bean

- [ ] `RagSearchExecutorConfig` 新增 `@Lazy @Bean("ragPostProcessExecutor")` = `Executors.newVirtualThreadPerTaskExecutor()` + `@PreDestroy` shutdown（仿 `ragSearchExecutor`）。**不复用** `ragSearchExecutor`。

## Phase 2 — 处理器拆分（无状态，B3）

- [ ] `RerankDocumentPostProcessor`：`process()` 内「rerank + 写 rerankScore」提取为包级 `rerankOnly(Query, docs)`（保留降级契约）；`process()` 改委托（对外行为不变）。
- [ ] `MmrDocumentPostProcessor`：`process()` 拆 `fetchDistanceMatrix(docs)`（DB 预取 + 失败→null）+ `selectByMmr(query, docs, distance)`（贪心，distance=null 走 relevance-only）；`process()` 串联两者（对外行为不变）。两方法无状态。

## Phase 3 — 复合处理器

- [ ] 新建 `RerankThenMmrPostProcessor`（design CompletableFuture 形态）：`supplyAsync(rerankOnly, ragPostProcessExecutor).exceptionally(透传)` ⊥ `fetchDistanceMatrix` 同步 → `join` → `selectByMmr`。
- [ ] **import**：`CompletableFuture`、`ExecutorService`、`@Qualifier`、`Logger`。
- [ ] 注入 `ragPostProcessExecutor` + 依赖（`reranker`/`rerankTopN`/`mmrLambda`/`mmrTopK`/`vectorStoreMapper`）。

## Phase 4 — 编排接入

- [ ] `buildPostProcessors`：rerank+mmr 均启用时合为 `RerankThenMmrPostProcessor`；list = `[RerankThenMmrPostProcessor, ParentDocumentPostProcessor]`。仅启用其一时退回单处理器（顺序仍 Rerank→MMR）。

**验证**：`mvnw test -Dtest='RerankDocumentPostProcessorTest,MmrDocumentPostProcessorTest,RerankThenMmrPostProcessorTest'`

## Phase 5 — 单测（B2 降级路径）

- [ ] rerank 异常 → `.exceptionally` 透传原 documents。
- [ ] rerank 返回空 → 透传。
- [ ] distance 异常 → null → relevance-only。
- [ ] 两者皆失败 → 透传 + relevance-only。
- [ ] blank query → 透传。

## Phase 6 — 验证

- [ ] `mvnw test` 全量绿。
- [ ] 延迟对比：串行 Rerank→MMR（PR-A 版）vs 并行（PR-C），确认 distance 等待被吸收（日志/埋点）。
- [ ] `detect_changes({scope:"compare", base_ref:"main"})` 命中预期符号。
