# PR-C: CompletableFuture 并行化（distance⊥Rerank）

**Parent**: `06-20-rag-rerank-mmr-reorder`（技术设计见其 `design.md` §4/§7）
**依赖**: PR-A（顺序调换后的 Rerank→MMR 点）
**复杂度**: 复杂（需 `design.md` + `implement.md`，见本任务）

## 背景

PR-A 把顺序调为 Rerank→MMR 后仍串行。本 PR 把 Rerank 的 LLM 等待与 MMR 的 distance DB 预取并行化，压缩延迟。**用 CompletableFuture，非 ScopedTasks**（架构 review H1：2 fork+1 join 场景 ScopedTasks 过度设计，且引入 B1 scope 超时撕穿 / B2 异常被静默吞两个陷阱）。详见本任务 `design.md`。

## 需求

- **R1 executor bean**：`RagSearchExecutorConfig` 新建 `ragPostProcessExecutor`（虚拟线程 per-task，独立于 `ragSearchExecutor`，资源隔离；不开全局开关）。
- **R2 处理器拆分**：`RerankDocumentPostProcessor` 拆 `rerankOnly(Query, docs)`（保留降级契约）；`MmrDocumentPostProcessor` 拆 `fetchDistanceMatrix(docs)` + `selectByMmr(query, docs, distance)` —— **无状态方法**（纯函数 + 只读字段，B3）。
- **R3 复合处理器**：新建 `RerankThenMmrPostProcessor`（CompletableFuture，design §4.1）：rerank `supplyAsync(ragPostProcessExecutor).exceptionally(透传)` ⊥ distance 主线程同步 → `.join()` → `selectByMmr`。
- **R4 编排**：`buildPostProcessors` 合为 `[RerankThenMmrPostProcessor, ParentDocumentPostProcessor]`。
- **R5 降级契约（B1/B2）**：rerank 失败 `.exceptionally` 透传；distance 失败 try-catch→null 走 relevance-only；两者失败透传+relevance-only；blank query 透传。**无 scope 超时撕穿**（B1 消除）。
- **R6 单测**：覆盖 rerank 异常透传 / distance 异常降级 / 两者皆失败 / blank query 四条降级路径。

## 执行前 gate

- pinning 验证：**仅 rerank 分支**（HTTP/resilience 包装是否 synchronized 阻塞）；distance 在调用线程同步跑，MyBatis/JDBC/HikariCP pinning 与本方案无关（design §7）。

## 验收

- [ ] 并行后端到端检索延迟 ≤ PR-A 串行版（distance 等待被 Rerank 重叠吸收）。
- [ ] 降级契约单测全绿（4 条路径）。
- [ ] 处理器无状态（B3，跨请求缓存共享安全）。
- [ ] `mvnw test` 绿；`detect_changes` 命中预期。

## 设计与执行

见本任务 `design.md`（CompletableFuture 形态 + B1/B2/B3 + pinning 范围）+ `implement.md`。
