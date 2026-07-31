# Design — RetrievalPath 接口抽象 + HybridSearchService 重构

## Authoritative Source

完整技术设计见 **`docs/design/entity-centric-retrieval.md` §6.5**（第 911-1003 行）。本文件仅记录子任务级设计补充和决策。

## 设计要点（指向主文档 + 子任务补充）

### 1. 接口与 Record 定义（§6.5 第 940-948 行）

`RetrievalPath` 接口 + `ScoredDocument` record 定义与主文档 §6.5 完全一致。

- `RetrievalPath.name()`：路径标识，用于 `ScopedTasks.fork(name, ...)` + 日志/trace。
- `RetrievalPath.search()`：接收 normalized query（非原始 query）、userId、teamId，返回 `List<ScoredDocument>`。
- `RetrievalPath.rrfWeighting()`：`SCORE_WEIGHTED`（vector/entity）或 `RANK_ONLY`（BM25）。
- `ScoredDocument(Document doc, int rank, double score)`：从 `HybridSearchService` 第 254 行的 `private record` 提升为顶层 `public record`。

### 2. 包路径决策（§10.2 第 1354-1355 行）

**决定**：`RetrievalPath` + `ScoredDocument` + `VectorRetrievalPath` + `Bm25RetrievalPath` 放入 `com.smart.rag.rag.retrieval` 包。

理由：
- `HybridDocumentRetriever`（现有适配器）已在此包，检索抽象收敛到同一包符合 CARP。
- 后续 `EntityRetrievalPath`（path-c）也在此包（§10.2 第 1360 行确认）。
- `HybridSearchService` 留在 `agent/service/`，通过构造注入 `List<RetrievalPath>` 依赖接口——符合 DIP。

### 3. 当前 vs 目标 fork 机制对比

| 维度 | 当前（硬编码） | 目标（RetrievalPath 驱动） |
|---|---|---|
| fork 数量 | 固定 2（vector + bm25） | `paths.size()` 动态 |
| fork 入口 | `scope.fork("vector-search", () -> this.vectorSearchWithScore(...))` + `scope.fork("bm25-search", () -> this.bm25Search(...))` | `scope.fork(path.name(), () -> path.search(...))` 遍历 paths |
| 降级判断 | 显式 `vectorTask`/`bm25Task` 逐个检查 | 遍历 `tasks.values()`，统计失败数 |
| rrfFusion 签名 | `rrfFusion(List<ScoredDocument>, List<ScoredDocument>)` | `rrfFusion(Map<RetrievalPath, Subtask<List<ScoredDocument>>>)` |
| RRF 加权分支 | 硬编码 vector 用加权、bm25 用纯排名 | `path.rrfWeighting() == SCORE_WEIGHTED ? score * 1/(k+rank) : 1/(k+rank)` |
| hybridRetrievalEnabled=false | 方法体内 `if` 跳过 hybrid，走 `vectorSearch` 单路 | `Bm25RetrievalPath` 不注册，paths 仅含 vector 适配器，fork 数=1 |
| 新增路径 | 改 Service 签名 + 方法体（OCP 违反） | 新增 `@Component implements RetrievalPath`（OCP 合规） |

### 4. ScoredDocument 提升迁移

- **来源**：`HybridSearchService` 第 254 行 `private record ScoredDocument(Document doc, int rank, double score) {}`。
- **目标**：`com.smart.rag.rag.retrieval.ScoredDocument`（public record）。
- **迁移影响**：`HybridSearchService` 内部直接引用改为 import + 使用；`VectorRetrievalPath`/`Bm25RetrievalPath` 同包直接使用；外部（`HybridDocumentRetrieverTest`）通过 `RetrievalPath.search()` 返回值间接使用，无需显式 import。
- **rrfScore metadata 写入**：当前 `rrfFusion` 在 `doc.getMetadata().put("rrfScore", ...)`——此行为不变，仍在 HybridSearchService 内部。

### 5. 适配器与 HybridSearchService 的依赖关系

**关键决策**：适配器不能持有 `HybridSearchService` 引用（会导致 Spring 循环依赖：Service → List<Path> → Service）。

方案：
- `VectorRetrievalPath` 注入 `VectorStore` + `RagRetrievalProperties`（直接实现向量检索逻辑）。
- `Bm25RetrievalPath` 注入 `VectorStoreMapper` + `QueryNormalizer` + `RagRetrievalProperties`（直接实现 BM25 检索逻辑）。
- 检索逻辑从 `HybridSearchService` 的 `private` 方法**搬迁**到适配器中，而非包装/委托。
- `HybridSearchService` 保留 `rrfFusion`、降级逻辑、`ScopedTasks` 编排。
- `HybridSearchService` 移除对 `VectorStore`/`VectorStoreMapper` 的直接依赖（构造器不再接受这两个参数）。

### 6. rrfFusion 重构细节

当前实现（`HybridSearchService:196-230`）有两个独立循环：vector 用 `score * 1/(k+rank)`，bm25 用 `1/(k+rank)`。

重构后：
```java
private List<Document> rrfFusion(
        Map<RetrievalPath, Subtask<List<ScoredDocument>>> results) {
    int k = properties.rrfK();
    Map<String, Double> scores = new HashMap<>();
    Map<String, Document> docMap = new HashMap<>();

    for (var entry : results.entrySet()) {
        RetrievalPath path = entry.getKey();
        List<ScoredDocument> docs = taskResultOrEmpty(entry.getValue(), path.name());
        boolean scored = path.rrfWeighting() == RrfWeighting.SCORE_WEIGHTED;

        for (ScoredDocument sd : docs) {
            String docId = sd.doc.getId();
            if (docId == null) continue;
            double contribution = scored
                    ? sd.score * (1.0 / (k + sd.rank))
                    : 1.0 / (k + sd.rank);
            scores.merge(docId, contribution, Double::sum);
            docMap.putIfAbsent(docId, sd.doc);
        }
    }
    // 排序 + limit(fusionTopK) + rrfScore metadata 写入 — 同现有逻辑
}
```

### 7. 降级逻辑泛化

当前（`HybridSearchService:106-119`）：
- 显式命名 `vectorFailed`/`bm25Failed`。
- 双失败 → throw，单失败 → warn。

重构后：
- 遍历 `tasks.values()` 统计失败数。
- 全部失败（`failedCount == paths.size()`）→ throw（错误消息泛化为 "所有检索路径均不可用" 或保留原消息以维持测试兼容——**需保留原消息以通过 AC1**）。
- 部分失败 → warn（log 各路径状态）。

**AC1 要求零测试修改**，因此错误消息 `"向量检索和 BM25 检索均不可用"` 须保留（因为 `HybridDocumentRetrieverTest` 第 235 行断言了该消息）。这意味着当前阶段降级消息仍硬编码为双路语义——待 path-c 上线时再泛化。

### 8. hybridRetrievalEnabled=false 时的单路模式

当前（`HybridSearchService:90-92`）：
```java
if (!properties.hybridRetrievalEnabled()) {
    return vectorSearch(normalized, vectorTopK, userId, teamId);
}
```

重构后：`Bm25RetrievalPath` 带 `@ConditionalOnProperty`，`false` 时不注册。`paths` 仅含 `VectorRetrievalPath`，`paths.size() == 1`。`hybridSearch()` 仍走 ScopedTasks fork 流程（1 个 fork + join + rrfFusion），效果与当前 `vectorSearch` 单路等价（RRF 对单路结果只是排序，不改变结果集）。

**注意**：当前 `hybridRetrievalEnabled=false` 时走 `vectorSearch`（不经过 ScopedTasks，直接 `vectorStore.similaritySearch`）。重构后统一走 ScopedTasks fork。行为等价但执行路径略有变化——需要验证这不会影响测试。如果测试 mock 了 `ScopedTasks` 的交互，需确认单路 fork 的结果与 `vectorSearch` 返回一致。

**修正**：为保持行为精确等价，考虑在 `paths.size() == 1` 时直接调用该路径（不走 fork），或确保 `ScopedTasks` 单 fork 的开销和结果与直接调用等价。更安全的做法是：重构后 `hybridRetrievalEnabled=false` 时 `paths` 仍含 vector 适配器，fork 1 路——RRF 对单路只是按分数降序排列 + limit(fusionTopK)，与当前 `vectorSearch` 的区别在于 `rrfScore` metadata 写入。如果现有测试不检查 metadata，则行为等价。

## Design Principle Mapping

| 原则 | 落实点 | 说明 |
|---|---|---|
| **OCP** | `RetrievalPath` 接口 + `List<RetrievalPath>` 构造注入 | 新增 Path C/D 只需新增 `@Component implements RetrievalPath`，`HybridSearchService` 零改动 |
| **DIP** | `HybridSearchService` 依赖 `RetrievalPath` 接口而非具体实现 | 与 §6.5 设计一致 |
| **SRP** | 检索逻辑从 `HybridSearchService` 分离到各适配器 | Service 只保留编排 + 融合 + 降级 |
| **ISP** | `RetrievalPath` 接口仅 3 个方法（`name`/`search`/`rrfWeighting`） | 粒度合理，无冗余方法 |
| **CARP** | 适配器放入 `rag/retrieval/` | 检索层内聚 |
| **KISS** | 不引入 Strategy 工厂，Spring 自动收集 `List<RetrievalPath>` | 两个实现不需要工厂 |

## Risks

1. **循环依赖**：适配器不能依赖 `HybridSearchService`——已通过直接注入 `VectorStore`/`VectorStoreMapper` 规避。
2. **测试兼容**：`HybridDocumentRetrieverTest` 直接通过构造器创建 `HybridSearchService`（mock VectorStore/VectorStoreMapper）——重构后构造器签名变为 `List<RetrievalPath>` + `RagRetrievalProperties` + `QueryNormalizer` + `ScopedTasks`，测试需要构造 mock RetrievalPath 列表。**但如果 AC1 要求零测试修改，则需要提供兼容构造器**。见 implement.md 的迁移策略。
3. **降级消息硬编码**：为通过 AC1，"向量检索和 BM25 检索均不可用" 消息保留——但这在 Path C 上线后会不准确。path-c 阶段负责泛化。
