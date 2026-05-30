# CompletableFuture 使用审查报告

**日期**: 2026-05-31  
**审查范围**: `src/main/java/com/smart/rag/**` 中 `CompletableFuture`、`Future`、`ExecutorService`、`join()`、`get()` 相关使用  
**审查口径**: 只读审计，重点检查阻塞 IO 是否误用 commonPool、异步是否被同步等待抵消、异常是否延迟到 `join/get` 才暴露、是否具备超时和取消策略、Future 使用是否有必要  
**最终建议**: REQUEST CHANGES

---

## 总览

| 级别 | 数量 | 结论 |
|------|------|------|
| HIGH | 1 | 启动初始化存在裸 `CompletableFuture.runAsync()`，会使用 `ForkJoinPool.commonPool` |
| MEDIUM | 4 | 检索、评测数据生成、FastTrack extract 存在线程池容量、超时/取消不完整或同步等待风险 |
| LOW | 3 | 多数 ETL / 模型刷新 Future 是合理阶段屏障，但仍需保留现有线程池隔离和超时策略 |

本次没有修改生产代码，也没有运行测试。审查结论基于源码静态阅读和 GitNexus 流程定位。

---

## HIGH

### H1: 启动模型初始化裸用 `CompletableFuture.runAsync()`，默认进入 commonPool

**文件**:

- `src/main/java/com/smart/rag/config/ModelProviderAutoConfiguration.java:77`

**证据**:

```java
CompletableFuture.runAsync(() -> {
    boolean success = refresher.refresh();
    ...
}).exceptionally(ex -> {
    ...
});
```

`runAsync()` 未传入 executor 时使用 `ForkJoinPool.commonPool`。该任务内部调用 `ModelRegistryRefresher.refresh()`，刷新路径会触发模型 provider 拉取和 registry 构建。虽然 provider 拉取本身在 `ModelRegistryRefresher` 内部使用虚拟线程执行器，但启动初始化这个外层任务仍占用 commonPool worker。

**风险**:

启动阶段如果 provider 初始化变慢、DNS/HTTP 阻塞或刷新逻辑扩展，commonPool 线程可能被阻塞任务占用。项目中其他默认依赖 commonPool 的逻辑会受到影响，表现为 ForkJoinPool worker 很快耗尽、CPU 利用率下降、异步任务堆积。

**建议修复**:

为启动初始化指定专用 executor。可选方案：

- 使用虚拟线程 per-task executor 承载启动刷新任务；
- 或注入一个命名的 Spring `TaskExecutor`，例如 `modelInitExecutor`；
- 禁止继续使用裸 `runAsync()`。

---

## MEDIUM

### M1: 混合检索每次请求提交两个阻塞任务，默认线程池容量偏小

**文件**:

- `src/main/java/com/smart/rag/agent/service/HybridSearchService.java:82`
- `src/main/java/com/smart/rag/agent/config/RagSearchExecutorProperties.java:14`

**证据**:

```java
CompletableFuture<List<ScoredDocument>> vectorFuture =
        CompletableFuture.supplyAsync(() -> vectorSearchWithScore(...), searchExecutor);
CompletableFuture<List<ScoredDocument>> bm25Future =
        CompletableFuture.supplyAsync(() -> bm25Search(...), searchExecutor);
```

`ragSearchExecutor` 默认配置：

```java
private int corePoolSize = 2;
private int maxPoolSize = 4;
private int queueCapacity = 20;
```

**判断**:

这不是 commonPool 误用，代码显式使用了 `ragSearchExecutor`。但每个混合检索请求固定提交 2 个阻塞检索任务，默认最大 4 线程意味着同时只有约 2 个请求能真正并行跑满 vector + BM25。更高并发下任务进入队列或触发 `CallerRunsPolicy`，请求线程可能反过来执行检索任务。

**风险**:

高并发 agent/RAG 查询下，检索线程池容易成为吞吐瓶颈，表现为请求同步等待、队列堆积、CPU 利用率偏低但响应变慢。

**建议修复**:

- 根据目标并发调整 `app.agent.search-executor.max-pool-size` 和队列容量；
- 评估 vector 检索和 BM25 是否应拆成两个 executor，避免其中一路慢查询拖累另一路；
- 增加线程池指标暴露：active count、queue size、rejection/caller-runs 次数。

---

### M2: 混合检索 `get(timeout)` 后未取消后台任务

**文件**:

- `src/main/java/com/smart/rag/agent/service/HybridSearchService.java:88`
- `src/main/java/com/smart/rag/agent/service/HybridSearchService.java:95`

**证据**:

```java
try {
    vectorResults = vectorFuture.get(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
} catch (Exception e) {
    vectorFailed = true;
    log.warn("Vector search degraded: {}", e.getMessage());
}

try {
    bm25Results = bm25Future.get(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
} catch (Exception e) {
    bm25Failed = true;
    log.warn("BM25 search degraded: {}", e.getMessage());
}
```

**判断**:

代码具备 5 秒等待超时，但超时或异常后没有调用 `future.cancel(true)`，底层 vector/BM25 查询可能继续占用检索线程直到自然返回。

**风险**:

当数据库、向量库、网络或 embedding 依赖变慢时，调用线程已降级返回，但线程池中的慢任务仍继续运行，导致后续请求可用线程减少，形成级联退化。

**建议修复**:

- 捕获 `TimeoutException` 时显式取消对应 future；
- 区分 `TimeoutException`、`ExecutionException`、`InterruptedException`，中断时恢复线程中断标记；
- 如果底层客户端不响应 interrupt，需要在 DB/HTTP 客户端层设置真实查询超时。

---

### M3: Evaluation 数据集生成并发调用 LLM，但没有整体 Future 超时

**文件**:

- `src/main/java/com/smart/rag/rag/evaluation/dataset/DatasetGenerator.java:91`
- `src/main/java/com/smart/rag/rag/evaluation/dataset/DatasetGenerator.java:106`
- `src/main/java/com/smart/rag/rag/evaluation/dataset/DatasetGenerator.java:124`

**证据**:

```java
ExecutorService executor = new ThreadPoolExecutor(
        concurrency, concurrency, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(concurrency * 10),
        new NamedThreadFactory("eval-dataset"),
        new ThreadPoolExecutor.CallerRunsPolicy());

futures.add(CompletableFuture.supplyAsync(() -> {
    ...
    List<GeneratedQuestion> questions = generateQuestions(chatClient, content);
    ...
}, executor));

List<EvaluationDatasetItem> allItems = futures.stream()
        .map(CompletableFuture::join)
        .flatMap(List::stream)
        .toList();
```

**判断**:

该模块仅在 `evaluation` profile 启用，使用了自建有界线程池，不是 commonPool。问题是 `join()` 没有整体超时，若某个 LLM 调用长期阻塞，接口会一直等待。

**风险**:

评测数据生成接口可能被单个慢 LLM 调用拖住。线程池虽然有界，但调用方请求线程仍同步等待全部结果。

**建议修复**:

- 为每个生成任务增加 `orTimeout` 或统一 `allOf(...).orTimeout(...)`；
- 超时后取消未完成任务；
- 如果评测数据生成耗时本来较长，考虑改为异步 job：接口只创建任务，后台执行，前端轮询状态。

---

### M4: FastTrack extract 阶段 `allOf().join()` 没有超时

**文件**:

- `src/main/java/com/smart/rag/rag/etl/FastTrackStrategy.java:194`
- `src/main/java/com/smart/rag/rag/etl/FastTrackStrategy.java:208`

**证据**:

```java
List<CompletableFuture<ExtractOutput>> futures = candidates.stream()
        .map(c -> CompletableFuture.supplyAsync(() -> {
            ...
            List<Document> docs = extractor.extract(c.bucket(), c.objectKey(), c.mimeType());
            ...
        }, ioExecutor))
        .toList();

CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
```

**判断**:

FastTrack extract 使用 `ioExecutor`，不是 commonPool。问题是缺少超时控制；同类 `StandardStrategy.joinAll()` 已经有 5 分钟 `orTimeout`。

**风险**:

单个 MinIO、文件解析或外部 IO 卡住会阻塞整个 fast-track 批次返回。该路径又是“小文档快速返回”的优化路径，卡住时会违背快速通道的设计目标。

**建议修复**:

复用 `StandardStrategy` 的等待策略，至少补：

```java
CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
        .orTimeout(5, TimeUnit.MINUTES)
        .join();
```

并在超时后标记对应文档失败，避免批次无明确结果。

---

## LOW

### L1: `ModelRegistryRefresher.refresh()` 的 `join()` 是合理阶段屏障

**文件**:

- `src/main/java/com/smart/rag/chat/service/ModelRegistryRefresher.java:42`
- `src/main/java/com/smart/rag/chat/service/ModelRegistryRefresher.java:72`
- `src/main/java/com/smart/rag/chat/service/ModelRegistryRefresher.java:91`

**证据**:

```java
private static final Executor FETCH_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

List<CompletableFuture<ProviderResult>> futures = providers.stream()
        .map(provider -> CompletableFuture.supplyAsync(() -> {
            try {
                List<ModelInfo> models = provider.fetchModels();
                return new ProviderResult(provider, models, null);
            } catch (Exception e) {
                return new ProviderResult(provider, List.of(), e);
            }
        }, FETCH_EXECUTOR))
        .toList();

List<ProviderResult> results = futures.stream()
        .map(CompletableFuture::join)
        .toList();
```

**判断**:

这里拉取模型是网络 IO，但已显式使用虚拟线程 executor。`join()` 是刷新注册表前必须等待所有 provider 结果的阶段屏障；异常也被转换为 `ProviderResult`，不会等到 `join()` 才首次暴露。

**保留建议**:

保留显式 executor。后续如 provider 数量增加，建议增加 provider 级并发限制或整体刷新超时。

---

### L2: `StandardStrategy` 的 `join()` 是 ETL 阶段屏障，整体设计合理

**文件**:

- `src/main/java/com/smart/rag/rag/etl/StandardStrategy.java:79`
- `src/main/java/com/smart/rag/rag/etl/StandardStrategy.java:105`
- `src/main/java/com/smart/rag/rag/etl/StandardStrategy.java:142`
- `src/main/java/com/smart/rag/rag/etl/StandardStrategy.java:188`

**证据**:

Extract 使用 `ioExecutor`，Transform 使用 `cpuExecutor`，Load 使用 `ioExecutor`。`joinAll()` 具备 5 分钟超时：

```java
CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
        .orTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
        .join();
```

**判断**:

这是批量 ETL 的阶段化并发，不是把无意义异步又同步化。每个阶段必须等上一阶段完成才能进入下一阶段，`join()` 在这里属于合理屏障。

**保留建议**:

保留 IO/CPU executor 隔离。后续可以统一抽取 ETL future 等待/超时/取消工具，避免 `FastTrackStrategy` 与 `StandardStrategy` 策略不一致。

---

### L3: `FastTrackStrategy.asyncVectorize()` 的异步使用符合业务目标

**文件**:

- `src/main/java/com/smart/rag/rag/etl/FastTrackStrategy.java:158`
- `src/main/java/com/smart/rag/rag/etl/FastTrackStrategy.java:174`
- `src/main/java/com/smart/rag/rag/etl/FastTrackStrategy.java:181`

**证据**:

```java
CompletableFuture<Void> future = CompletableFuture
        .supplyAsync(() -> transformer.transform(...), cpuExecutor)
        .thenAcceptAsync(chunks -> {
            loader.load(chunks);
            vectorStoreMapper.deleteFastTrackRows(c.documentId());
            statusManager.updateChunkCount(c.documentId(), chunks.size());
        }, ioExecutor)
        .exceptionally(ex -> {
            statusManager.markVectorFailed(c.documentId(), ex);
            return null;
        });
```

**判断**:

这是 FastTrack 的核心业务语义：BM25 原文先可用，向量化后台补齐。Transform 和 Load 分别进入 CPU/IO executor，异常也在链路尾部处理并标记 `VECTOR_FAILED`。

**保留建议**:

保留当前异步链路。可补充单任务超时，避免异步向量化长期占用 active task 集合。

---

## 其他 Future 使用点

### SandboxService

**文件**:

- `src/main/java/com/smart/rag/chat/tool/sandbox/SandboxService.java:134`
- `src/main/java/com/smart/rag/chat/tool/sandbox/SandboxService.java:148`

`SandboxService` 使用虚拟线程 executor 和 `Semaphore` 限流，并通过 `future.get(timeout + 2000ms)` 控制容器执行等待。超时时会 kill 容器。该模式不属于 commonPool 风险。

### BailianRerankPostProcessor

**文件**:

- `src/main/java/com/smart/rag/rag/retrieval/BailianRerankPostProcessor.java:79`
- `src/main/java/com/smart/rag/rag/retrieval/BailianRerankPostProcessor.java:114`
- `src/main/java/com/smart/rag/rag/retrieval/BailianRerankPostProcessor.java:116`

Rerank 使用专用 `ThreadPoolExecutor` 和 `future.get(timeout)`，且 HTTP 客户端配置了连接/响应超时。该模式不属于 commonPool 风险。仍需确认 catch 分支是否对超时 future 做了 cancel；本次审查没有展开该方法完整异常分支。

---

## 优先级建议

1. **先修 H1**: 禁止裸 `CompletableFuture.runAsync()`，启动模型初始化必须指定 executor。
2. **再修 M2/M1**: 混合检索超时后取消任务，并根据实际并发调大或拆分 `ragSearchExecutor`。
3. **补齐超时一致性**: `FastTrackStrategy.extractAll()` 对齐 `StandardStrategy.joinAll()`；`DatasetGenerator` 增加整体超时或改后台 job。
4. **保留合理异步**: 不建议删除 `StandardStrategy`、`FastTrackStrategy.asyncVectorize()`、`ModelRegistryRefresher.refresh()` 的 Future。它们分别承担阶段并发、后台向量化、provider 并发拉取的业务价值。

---

## 结论

项目中没有大面积把阻塞 IO 扔进 `ForkJoinPool.commonPool` 的问题；多数高风险路径已经显式指定业务线程池或使用虚拟线程。

真正需要处理的是：

- 一个裸 `runAsync()` 的 commonPool 风险；
- 检索线程池容量与超时取消策略不完整；
- 部分批处理 Future 缺少整体超时；
- 一些 `join/get` 虽然是同步等待，但多数属于业务上必要的阶段屏障，不应简单删除。
