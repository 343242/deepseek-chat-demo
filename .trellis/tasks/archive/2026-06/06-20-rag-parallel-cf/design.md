# Design — PR-C CompletableFuture 并行化

> 技术设计主体在 parent `06-20-rag-rerank-mmr-reorder/design.md` §4/§7（CompletableFuture 形态、B1/B2/B3、pinning 范围）。本文补 PR-C 执行视角的要点。

## 复合处理器 CompletableFuture 形态（parent design §4.1）

```java
CompletableFuture<List<Document>> rerankFut = CompletableFuture
    .supplyAsync(() -> rerankOnly(query, documents), ragPostProcessExecutor)
    .exceptionally(ex -> { log.warn("rerank failed, passthrough", ex); return documents; }); // B2
Map<String,Double> distance = fetchDistanceMatrix(documents);  // 主线程同步，try-catch→null
List<Document> reranked = rerankFut.join();                     // 等 rerank（distance 已算完，重叠）
return selectByMmr(query, reranked, distance);                  // distance==null → relevance-only
```

耗时 ≈ max(distance, rerank) ≈ rerank，distance 的 DB 等待被吸收。

## B1/B2/B3 落地

- **B1（超时撕穿降级契约）消除**：无 scope。Rerank 超时由 `ResilientRerankClient`/HTTP client 控制，走 `.exceptionally` 降级，不抛 `ScopeTimeoutException`。
- **B2（异常被 CollectAllPolicy 静默吞）消除**：rerank 异常 → `.exceptionally(透传)`；distance 异常 → try-catch→null→`selectByMmr` relevance-only。无静默路径。
- **B3（无状态）**：`rerankOnly`/`fetchDistanceMatrix`/`selectByMmr` 均无状态（参数传值，无实例中间字段），`cachedPostProcessors` 跨请求共享安全。

## pinning（仅 rerank 分支需验）

distance 在**调用线程（平台线程）同步**跑 → MyBatis/JDBC/HikariCP 的 synchronized pinning 与本方案无关。仅 rerank 分支（虚拟线程）需验 `AbstractRerankClient`/`ResilientRerankClient` 的 HTTP + resilience 包装是否 synchronized 阻塞（parent design §7）。必要时 `-Djdk.tracePinnedThreads=full`。

## executor bean

`ragPostProcessExecutor` = `Executors.newVirtualThreadPerTaskExecutor()`，在 `RagSearchExecutorConfig` 新建（独立于 `ragSearchExecutor`，资源隔离），`@Lazy` + `@PreDestroy` 生命周期仿 `ragSearchExecutor`。**不开** `spring.threads.virtual.enabled` 全局开关。

## 处理器拆分（无状态约束）

- `RerankDocumentPostProcessor.rerankOnly(Query, docs)`：原 `process` 内「rerank + 写 rerankScore」逻辑，保留降级契约（失败/空/blank 透传）。
- `MmrDocumentPostProcessor.fetchDistanceMatrix(docs)`：DB pairwise 预取，失败→null。
- `MmrDocumentPostProcessor.selectByMmr(query, docs, distance)`：贪心选择；distance=null 走 relevance-only（rerankScore 排序取 topK）。
- 三者纯函数 + 只读实例字段（lambda/topK/mapper），无中间状态写入字段。
