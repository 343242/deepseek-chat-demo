# Design — RAG 链路调换 Rerank/MMR 顺序 + 并行化

> 配套 `prd.md`（需求/约束/验收）与 `implement.md`（执行清单）。本文档只讲技术设计、约束依据与权衡。

## 1. 现状架构（基线）

编排核心在 `RagAdvisorFactory`，两条入口共用 `buildPostProcessors()`：

```
召回 hybrid (vector30 ⊥ bm2530 → RRF, 已并行, HybridSearchService.fork)
  └─ postProcessors (buildPostProcessors L179-201, 顺序串行)
        1. MmrDocumentPostProcessor(mmrLambda=0.7, mmrTopK=10)   — DB pairwiseCosineDistance + CPU 贪心
        2. RerankDocumentPostProcessor(reranker, topN=10)         — 单次批量 LLM API（RagConfig L85 硬编码 10）
        3. ParentDocumentPostProcessor                            — DB batchFetchParents + 按 rerankScore>rrfScore 重排父文档
```

两条入口：

- **Advisor 路径** `create()`（L85-106）：把 postProcessors list 交给 Spring AI `RetrievalAugmentationAdvisor`，框架按顺序调用每个 `process()`。
- **chat 直检路径** `retrieve()`（L115-128）：不复用 Advisor 壳，自己 `for (pp : getPostProcessors()) docs = pp.process(...)` 逐个跑。注释明确「100% 复用」隔离+MMR+Rerank+Parent 组件。

当前顺序的两个问题（详见 prd 背景）：
1. MMR 设计本意是 Rerank 之后跑（类注释 L16 + `resolveRelevanceScore` 优先取 rerankScore L151），但代码相反。
2. 仅调换顺序会让 MMR 失效（mmrTopK=10 == rerank topN=10 命中早退 L62）。

## 2. 关键约束：Spring AI `RetrievalAugmentationAdvisor` 是 final

**反编译证据**（`spring-ai-rag-1.1.6.jar`）：

```
public final class RetrievalAugmentationAdvisor implements BaseAdvisor { ... }
```

postProcessor 处理在 `before()` 内，字节码显示为**硬编码顺序 iterator**：

```
getfield documentPostProcessors : List
invokeinterface List.iterator()
loop: hasNext → next → checkcast DocumentPostProcessor → invokeinterface process(Query, List)
```

**含义**：
- 不能子类化覆盖编排逻辑。
- 没有可注入的自定义「编排策略」扩展点（`getDocumentsForQuery` 是 private）。
- advisor 内部确有 `TaskExecutor` + `CompletableFuture`，但那是 **QueryExpander 多 query 并行检索**，对单 query 的 postProcessor 链无效。

**推论**：Advisor 路径下，并行的唯一封装形态是「**单个复合 DocumentPostProcessor**」——对外仍是一个 `process()` 调用，对内用结构化并发并行。Rerank+MMR 合并为 `RerankThenMmrPostProcessor`；Parent 保持独立（理由见 §5）。

## 3. 目标架构与数据流

```
召回 hybrid 60 (vector30 ⊥ bm2530 → RRF, rrfFusion.limit=fusionTopK=60, 见 §6.5 解耦)
  └─ postProcessors
        1. RerankThenMmrPostProcessor (复合, 内部并行)
             ├── fork A: pairwiseCosineDistance(60 docIds)   ← 只依赖召回 docIds，与 Rerank 无关
             ├── fork B: reranker.rerank(query, 60 docs, topN=20)  ← 写 rerankScore, 取 20
             ├── join A,B
             └── 串行: MMR 贪心 (lambda=0.7, 从 20 选 10) 用预取距离矩阵 + rerankScore
        2. ParentDocumentPostProcessor                       — 串行末步，按 rerankScore 重排父文档
```

关键数据流变化：
- **Rerank 输入**：从「MMR 去重后的 10 条」变为「召回全量 60 条」。→ Rerank 处理量 6×（成本/算力↑），这是顺序调换的必然代价，A/B 验证其质量收益是否值回（见 §8）。
- **MMR 相关性信号**：从 fallback（rrfScore/0.5）变为真实的 **rerankScore**（设计本意），多样性计算更准。
- **distance 矩阵范围 + 50 截断（召回 60 下为真实问题）**：召回 60 > `MAX_PAIRWISE_DOCS=50`，`pairwiseCosineDistance`（`VectorStoreMapper` L153-162）会 `subList(0,50)` 截断 → Rerank top20 中落在 RRF 第 51-60 位的文档 pairwise distance 缺失，MMR 贪心 key miss → sim=0（`MmrDocumentPostProcessor` L119-120），被误判「无冗余」。
- **修复（选项 B，召回 60 下必须做）**：`MAX_PAIRWISE_DOCS` 从硬编码 50 改为与 `fusionTopK` 联动，阈值 = `max(50, fusionTopK)` = 60，让 distance 覆盖全 60 条。见 §6.5。

## 4. 复合处理器设计 `RerankThenMmrPostProcessor`

### 4.1 结构

```java
public class RerankThenMmrPostProcessor implements DocumentPostProcessor {
    private final RerankCapable reranker;
    private final int rerankTopN;        // 20
    private final double mmrLambda;      // 0.7
    private final int mmrTopK;           // 10
    private final VectorStoreMapper vectorStoreMapper;
    private final ExecutorService ragPostProcessExecutor;  // 专用虚拟线程 bean，仅供 Rerank 异步

    public List<Document> process(Query query, List<Document> documents) {
        // 守卫：空/单条/blank query 直接透传（复用 MMR + Rerank 各自守卫语义）

        // Rerank 异步到虚拟线程 bean（LLM IO，百 ms 级）—— B2：exceptionally 降级透传
        CompletableFuture<List<Document>> rerankFut = CompletableFuture
            .supplyAsync(() -> rerankOnly(query, documents), ragPostProcessExecutor)
            .exceptionally(ex -> { log.warn("rerank failed, passthrough", ex); return documents; });

        // distance 主线程同步预取（DB IO，~十 ms）—— 与 Rerank 重叠
        //   注意：distance 跑在调用线程（平台线程），不在虚拟线程 → 无 JDBC/MyBatis pinning 风险
        Map<String,Double> distance = fetchDistanceMatrix(documents);  // try-catch 失败 → null

        // 汇合：等 Rerank（此时 distance 通常已算完）
        List<Document> reranked = rerankFut.join();

        // 串行 MMR 贪心：reranked(20) 上用 distance + rerankScore
        //   distance==null → selectByMmr 走 relevance-only 降级（用 rerankScore）
        return selectByMmr(query, reranked, distance);
    }
    // 处理器无状态（B3）：所有中间状态走局部变量，cachedPostProcessors 跨请求共享安全
}
```

### 4.2 并行可行性（CompletableFuture，非 ScopedTasks）

- **distance 预取**输入 = 召回 docIds，与 Rerank 结果无关 → 可重叠。
- 形态：**Rerank 异步到 `ragPostProcessExecutor`（虚拟线程 bean），distance 在调用线程同步**。调用线程算 distance（DB IO ~十 ms）期间，Rerank 在虚拟线程跑（LLM IO 百 ms）；distance 完成后 `.join()` 等 Rerank。耗时 ≈ max(distance, rerank) ≈ rerank。**净收益 = distance 的 DB 等待被吸收**。
- **为何不用 ScopedTasks**（架构 review H1）：本场景只有「2 fork + 1 join」，ScopedTasks 的 scope 超时/`executorOwnedByScope`/`CollectAllPolicy`/`ContextCarrier` 是过度设计，且引入 B1（`ScopeTimeoutException` 撕穿降级契约）与 B2（异常被 `CollectAllPolicy.onFailure` 静默吞）两个陷阱。CompletableFuture + `.exceptionally()` 回到普通 try-catch 风格，降级契约清晰。
- **pinning 范围缩小**（附带收益）：distance 在调用线程（平台线程）同步跑 → **无 JDBC/MyBatis/HikariCP pinning 风险**；只有 Rerank 在虚拟线程 → pinning 验证只需覆盖 Rerank 的 HTTP/resilience 包装（§7）。

### 4.3 降级契约矩阵（CompletableFuture，无 scope → 无 B1 超时撕穿）

| 故障 | 行为 |
|---|---|
| Rerank API 失败/超时 | `.exceptionally(ex -> documents)` 透传原 documents；MMR 用 rrfScore fallback。**无 ScopeTimeoutException**（B1 规避）—— Rerank 超时由 `ResilientRerankClient`/HTTP client 控制 |
| Rerank 返回空 | `rerankOnly` 透传原 documents（RerankDocPP L61-64 既有契约） |
| distance 失败 | `fetchDistanceMatrix` try-catch → null；`selectByMmr` 走 relevance-only（rerankScore 排序取 topK，MmrDocPP L82-88 既有路径） |
| 两者都失败 | rerank 透传 + distance=null → MMR 用 rrfScore relevance-only 取 topK，不抛出 |
| blank query | `rerankOnly` 透传（RerankDocPP L44）；MMR 用 rrfScore |

降级原则沿用「不中断检索链路」硬契约。CompletableFuture 方案下**无 scope 超时行**（没有 scope），降级全靠 `.exceptionally()` + try-catch，语义清晰、不撕穿。

### 4.4 复合 vs 拆两个处理器

为何不保留 `RerankDocumentPostProcessor` + `MmrDocumentPostProcessor` 两个独立 bean（仅调换 list 顺序）？因为那样**无法并行**——Advisor 框架对 list 是顺序 iterator（§2）。只有合并进单个 `process()` 才能在内部 fork。代价：复合处理器内部要复刻两段逻辑（或委托给现有的两个处理器实例的私有方法）。**采用方案：复合处理器内部持有并复用 `RerankDocumentPostProcessor` 与 `MmrDocumentPostProcessor` 实例**，把它们的 process 逻辑拆成「可被 fork 的子步骤」+「MMR 贪心」。需小重构现有两个处理器暴露内部步骤（见 implement）。

## 5. 为什么 Parent 不并行（数据依赖证明）

Parent 的输入 = **Rerank+MMR 存活后**的子块文档的 parentId 集合。

- 若在 Rerank 期预取 Parent：只能基于召回 60 条的 parentIds 预取 → 冗余（最终 MMR 后仅 ~10 个父文档，却预取 20-40 个），DB IO 浪费。
- Parent 是链路**最末步**，无下游可与之重叠。
- `batchFetchParents` 是**单条批量 SQL**，本身 ~十 ms，快。
- 结论：为省几十 ms 引入冗余预取 + 复杂度不划算。**Parent 维持独立、串行、末步**。复合处理器只含 Rerank+MMR。

> 这与 prd「约束：Parent 维持串行末步」对应。用户最初设想「MMR 预取 + 子父替换并行等 Rerank」在此被修正——子父替换的 parentIds 依赖精排结果，不可提前。

## 6. topN 提参方案

- 新增 `RagRetrievalProperties.rerankTopN`（int，默认 20），同步更新 `withOverrides`（评估模块用）。
- `application-dev.yml` 加 `rerank-top-n: 20`。
- `RagConfig.rerankDocumentPostProcessor`（L80-86）改为读 properties。
- **候选池校验**：在 `RagRetrievalProperties` compact constructor 加 `rerankTopN > mmrTopK` 与 `fusionTopK >= rerankTopN` 校验，不满足则抛（fail-fast，避免 MMR 静默失效 / Rerank 候选不足）。

## 6.5 fusion-top-k 解耦 + pairwise 阈值联动（选项 B）

### 问题：rrfFusion 复用 vectorTopK 当最终 limit（耦合缺陷）

`HybridSearchService.rrfFusion`（L220）`.limit(properties.vectorTopK())` 把「稠密向量召回量」配置当作「融合后最终召回量」，职责耦合。导致 vector 30 + bm25 30 两路融合后被 limit 回 30，bm25 贡献的超出部分被丢弃，最终召回仅 30 而非预期的 ~60。

### 解耦方案

- 新增 `RagRetrievalProperties.fusionTopK`（默认 **60**，= vectorTopK + bm25TopK 上限），`withOverrides` 透传。
- `rrfFusion` L220 改为 `.limit(properties.fusionTopK())`。
- `vector-top-k` 回归本职（只管 vectorSearch=30）；`bm25-top-k` 维持 30。
- **校验**：`fusionTopK >= rerankTopN`（否则 Rerank 候选不足），fail-fast。
- `application-dev.yml` 加 `fusion-top-k: 60`。

### pairwise 阈值联动（选项 B，召回 60 下必须）

召回 60 > `MAX_PAIRWISE_DOCS=50` 真实截断（§3）。修复：

- `VectorStoreMapper.pairwiseCosineDistance` 的截断阈值从硬编码常量 50 改为**参数化**，阈值 = `max(50, fusionTopK)` = 60（保留 50 作为 O(n²) 下限防御）。
- 实现方式：`pairwiseCosineDistance(List<String> docIds, int maxDocs)` 增参，或从 properties 注入；复合处理器调用时传 `fusionTopK`。
- **impact**：`MAX_PAIRWISE_DOCS` 可能有其他引用（memory R1-L5），需 `impact` 确认无额外受影响调用方。

## 7. 并发执行器设计（专用虚拟线程 bean，不开全局开关）

### 决策

**不复用** `ragSearchExecutor`，**不开** `spring.threads.virtual.enabled` 全局开关。在 `RagSearchExecutorConfig` 新建一个**独立的虚拟线程 per-task bean** `ragPostProcessExecutor`，专供 `RerankThenMmrPostProcessor` 的 Rerank⊥distance 并行。

### 理由

1. **资源隔离**：Rerank 是慢 LLM IO（百 ms 级），与 hybrid 检索（vector/bm25）共用同一个 executor 实例会互相影响——慢任务挤占并发。独立实例隔离，互不拖累。
2. **虚拟线程契合阻塞 IO**：Rerank（LLM API）与 distance（DB pairwise）都是阻塞 IO；虚拟线程 per-task 在阻塞时释放载体平台线程，大量并发请求各自的两分支都不耗尽平台线程。
3. **形态一致**：与 `ragSearchExecutor`（同为虚拟线程 per-task）保持一致，复用 `@Lazy` + `@PreDestroy` 生命周期模板。
4. **不开全局开关**：避免 Tomcat 全链路虚拟线程化的不可控面；仅在本并行点用专用 bean，范围最小化。

### bean 设计（`RagSearchExecutorConfig` 内新增）

```java
private ExecutorService ragPostProcessExecutorService;

@Lazy
@Bean("ragPostProcessExecutor")
public ExecutorService ragPostProcessExecutor() {
    ragPostProcessExecutorService = Executors.newVirtualThreadPerTaskExecutor();
    log.info("RAG post-process executor: virtual thread per-task (rerank/mmr parallelism)");
    return ragPostProcessExecutorService;
}
// 配套 @PreDestroy shutdown（仿 ragSearchExecutor，awaitTermination 30s）
```

> Rerank 是单次批量 API，内部不拆分（跨候选打分语义 + 成本/限流）；该 executor 并发的只是「distance 预取」与「Rerank 调用」两个分支。虚拟线程无 core/max/queue 参数，无需提参。

### pinning 风险（仅 Rerank 分支需验，distance 在主线程无此风险）

CompletableFuture 方案下 distance 在**调用线程（平台线程）同步**跑，不在虚拟线程 → **MyBatis/PG JDBC/HikariCP 的 synchronized pinning 与本方案无关**（架构 review H1）。只有 Rerank 在虚拟线程 bean 上，需验证：

- **rerank 分支**：`AbstractRerankClient` → `ResilientRerankClient`（resilience 包装）。Resilience4j `Bulkhead`/旧 `CircuitBreaker` 用 synchronized 会 pin；底层 HTTP 客户端（HttpClient/OkHttp/WebClient）类型需确认 pinning-safe。

→ implement Phase 0.3 gate。必要时 `-Djdk.tracePinnedThreads=full` 探测 rerank 调用栈。

### 复合处理器接入（CompletableFuture）

`ragPostProcessExecutor` 经 `CompletableFuture.supplyAsync(task, executor)` 供 Rerank 异步使用。**不使用 ScopedTasks** → 规避 B1（`executorOwnedByScope` 默认 true 致 scope 关闭 bean）与 B2（异常被 CollectAllPolicy 吞）两个陷阱，也无需 `executorOwnedByScope` / 三参 `open` / scope 嵌套。

> 历史记录：原 ScopedTasks 三参 open 方案的 `.executorOwnedByScope(false)` CRITICAL 点、executorMode 忽略结论，随方案弃用不再适用。若未来回归 ScopedTasks，须重拾 `.executorOwnedByScope(false)`。

### 顺带发现（不在本任务范围）

`HybridSearchService` 构造器 L56-62 的 `@Qualifier("ragSearchExecutor") Executor searchExecutor` 参数实际未使用（直接 `new DefaultScopedTasks()`），真实 executor 由 `DefaultScopedTasks` 内部 factory 按 `ScopedTaskProperties.executorMode`（默认 VIRTUAL_THREAD_PER_TASK）决定。此为既有现象，本任务**不动**。

## 8. A/B 评估设计

用现有 `EvaluationRunner`（src/main/.../evaluation/runner/）跑两组配置：

- **Baseline**：旧顺序 MMR→Rerank（`mmrTopK=10, rerankTopN=10`）。
- **New**：新顺序 Rerank→MMR（`rerankTopN=20, mmrTopK=10`）。

对比指标：nDCG@k、命中率（hit rate）、端到端检索延迟（并行 vs 串行）。判定：New 不退化即通过；记录数据到任务目录 `research/` 或 `eval-results.md`。注意顺序调换会让 Rerank 处理 6× 文档，需观察延迟/成本变化。

## 9. 双路径一致性

`create()`（Advisor）与 `retrieve()`（chat 直检）必须保持编排一致：

- 两者都改为 postProcessor list = `[RerankThenMmrPostProcessor, ParentDocumentPostProcessor]`。
- `buildPostProcessors()` 是唯一构造点，两路径共用 → 只改一处即可两路径同步（`getPostProcessors()` 已缓存）。

## 10. 兼容性与回滚

- **回滚**：纯 Java 改动，回滚 = revert commit。无 schema/数据迁移。
- **配置兼容**：新增 `rerank-top-n`，未配置时默认 20；旧 yml 不填仍可启动。
- **测试**：现有 `MmrDocumentPostProcessorTest`、`RerankDocumentPostProcessorTest`、`ParentDocumentPostProcessorTest` 需适配（MMR/Rerank 被复合化后，单测可能需调整为测复合处理器 + 测内部子步骤）。
- **风险**：顺序调换改变召回质量（R1-M8 等历史修复依赖的顺序假设需复核）、Rerank 处理量 6×（成本/限流）。

## 11. 待执行前确认项汇总（implement Phase 0 gate）

1. `impact()` 跑 `buildPostProcessors`、`RerankDocumentPostProcessor`、`MmrDocumentPostProcessor`、`RagConfig.rerankDocumentPostProcessor`、`RagRetrievalProperties`，**以及扩展项**：`HybridSearchService.rrfFusion`、`VectorStoreMapper.pairwiseCosineDistance`/`MAX_PAIRWISE_DOCS`（后者见 memory R1-L5，确认无其他引用受影响）。
2. ✅ 已确认：召回 60（fusionTopK=60）> `MAX_PAIRWISE_DOCS=50` 真实截断 → **必须做选项 B**（阈值联动到 60，§6.5），否则 MMR distance 不完整。
3. ✅ 已查证：`ScopeOptions.Builder` 默认 `executorOwnedByScope=true`，`DefaultTaskScope.close()` L185-187 会据此 `shutdownOwnedExecutor()` → **复合处理器必须显式 `.executorOwnedByScope(false)`**，否则关掉 Spring bean。三参 `open` 传入的 executor 直接注入（L93），executorMode 被忽略，不额外创建 executor。
4. pinning 验证（虚拟线程风险）：`AbstractRerankClient`/`ResilientRerankClient` 的 HTTP + resilience 包装是否 synchronized 阻塞；PG JDBC 驱动版本（distance DB 调用）是否 pinning-safe。
5. 确认 `ScopedTasks` fork/join API 形态（参照 HybridSearchService L98-104）。
