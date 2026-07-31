# RetrievalPath 接口 + Vector/Bm25 适配器 + HybridSearchService 重构

## Goal

将 `HybridSearchService` 中硬编码的 Path A（vector）/Path B（BM25）双路召回抽象为 `RetrievalPath` 接口，使 `HybridSearchService` 依赖 `List<RetrievalPath>` 并通过 Spring 自动收集 `@Component` 实现。**本次为纯行为保持重构——Path A/B 输出与重构前完全等价，不引入任何实体代码。**

## Confirmed Facts（代码库核验）

1. **当前 fork 机制**：`HybridSearchService.hybridSearch()` 使用自研 `ScopedTasks`（`com.smart.rag.infrastructure.concurrent.ScopedTasks`）做结构化并发——`scope.fork(name, callable)` 派生子任务、`scope.join()` 等待、`Subtask<T>` 获取结果或异常。非 `StructuredTaskScope`、非 `ExecutorService.submit()`。
2. **ScoredDocument 当前位置**：`HybridSearchService` 的 `private record ScoredDocument(Document doc, int rank, double score) {}`（第 254 行），内部私有 record，不可被外部访问。
3. **当前 rrfFusion 签名**：`private List<Document> rrfFusion(List<ScoredDocument> vectorResults, List<ScoredDocument> bm25Results)`（第 196 行）。内部对 vector 路用 `score * 1/(k+rank)` 加权 RRF，对 BM25 路用 `1/(k+rank)` 纯排名 RRF。
4. **向量检索方法**：`private List<ScoredDocument> vectorSearchWithScore(String queryText, int topK, long userId, @Nullable Long teamId)`（第 159 行）。内部调用 `vectorSearchOrThrow` + rank 编号 + `doc.getScore()` 获取向量分数。
5. **BM25 检索方法**：`private List<ScoredDocument> bm25Search(String queryText, int topK, long userId, @Nullable Long teamId)`（第 173 行）。内部调用 `queryNormalizer.sanitizeForTsQuery`、`vectorStoreMapper.bm25Search`，score 固定为 `0.0`。
6. **降级处理**：双路均失败抛 `ServiceException(INTERNAL_ERROR, "向量检索和 BM25 检索均不可用")`；单路失败 log.warn + 优雅降级返回另一路结果（`taskResultOrEmpty` 方法，第 234 行）。
7. **当前 BM25 条件化**：BM25 并非通过 `@ConditionalOnProperty` 控制注册，而是在 `hybridSearch()` 方法体内通过 `properties.hybridRetrievalEnabled()` 决定是否走 hybrid 模式（`false` 时直接走 `vectorSearch` 单路）。BM25 路在 `hybridRetrievalEnabled=true` 时**总是** fork，不单独条件化。
8. **依赖注入**：`HybridSearchService` 有两个构造器——一个接受 `Executor`（创建 `DefaultScopedTasks`），一个接受 `ScopedTasks` 直接注入（测试用）。
9. **调用方**：`HybridDocumentRetriever`（`rag/retrieval/`）和 `HybridSearchTool`（`agent/tool/`），两者均仅调用 `hybridSearchService.hybridSearch(queryText, userId, teamId)`。
10. **现有测试**：`HybridDocumentRetrieverTest`（17 个测试用例）是唯一覆盖 `HybridSearchService` 行为的测试文件，通过 mock `VectorStore`/`VectorStoreMapper` 验证向量模式、混合模式 RRF、降级、团队隔离、sanitizeQuery。
11. **RagRetrievalProperties** 当前 `fusionTopK` 默认 60，无 `entity` 嵌套字段（属 path-c-retrieval 子任务）。

## Requirements

### R1：RetrievalPath 接口
- 创建 `RetrievalPath` 接口，包含 `String name()`、`List<ScoredDocument> search(String query, long userId, @Nullable Long teamId)`、`RrfWeighting rrfWeighting()`。
- 内嵌 `RrfWeighting` 枚举：`SCORE_WEIGHTED`、`RANK_ONLY`。
- 接口位于 `com.smart.rag.agent.service`（与 `HybridSearchService` 同包，§10.2 备选项 `rag/retrieval/` 在 Open Questions 中讨论）。

### R2：ScoredDocument 提升
- 将 `HybridSearchService.ScoredDocument` 从 `private record` 提升为 `com.smart.rag.agent.service` 包级顶层 `public record ScoredDocument(Document doc, int rank, double score)`。
- 同包内 `HybridSearchService`、`VectorRetrievalPath`、`Bm25RetrievalPath` 可直接访问；外部（如 `rag/retrieval/` 的 `EntityRetrievalPath`，属 path-c）通过 `implements RetrievalPath` 的返回类型间接使用。

### R3：VectorRetrievalPath 适配器
- `@Component` 类，实现 `RetrievalPath`。
- 包装现有 `vectorSearchWithScore` 逻辑（不复制代码——委托 `HybridSearchService` 或提取为共享方法）。
- `name()` 返回 `"vector-search"`（与当前 fork 名称一致）。
- `rrfWeighting()` 返回 `SCORE_WEIGHTED`。

### R4：Bm25RetrievalPath 适配器
- `@Component` + `@ConditionalOnProperty(prefix = "app.rag", name = "hybridRetrievalEnabled", havingValue = "true")`。
- 包装现有 `bm25Search` 逻辑。
- `name()` 返回 `"bm25-search"`（与当前 fork 名称一致）。
- `rrfWeighting()` 返回 `RANK_ONLY`。

### R5：HybridSearchService 重构
- 构造器注入 `List<RetrievalPath> paths`（替换 `VectorStore`/`VectorStoreMapper` 对检索的直接依赖——适配器内部持有这些依赖）。
- `hybridSearch()` 方法体改为遍历 `paths` 做 fork（`scope.fork(path.name(), () -> path.search(...))`），fork 数量由 `paths.size()` 决定。
- `rrfFusion` 签名改为接收 `Map<RetrievalPath, Subtask<List<ScoredDocument>>>`，按 `path.rrfWeighting()` 选择加权/纯排名 RRF 计算。
- 降级逻辑保持：全部失败抛异常，部分失败 log.warn + 优雅降级。
- **零行为改变**：`hybridRetrievalEnabled=false` 时，Spring 不注册 `Bm25RetrievalPath`，`paths` 仅有 `VectorRetrievalPath` 一个元素，效果等同于当前 `vectorSearch` 单路模式。
- 保留接受 `ScopedTasks` 的测试用构造器。

### R6：向后兼容
- `HybridDocumentRetriever` 和 `HybridSearchTool` 的调用方式 `hybridSearch(queryText, userId, teamId)` 不变。
- `RagRetrievalProperties` 本子任务不改（`entity` 嵌套字段属 path-c-retrieval）。

## Acceptance Criteria

- [ ] AC1：`HybridDocumentRetrieverTest` 全部 17 个现有测试用例绿色、零修改（行为保持证明）。
- [ ] AC2：`hybridRetrievalEnabled=true` 时注册 2 个 RetrievalPath（vector + bm25），`hybridSearch` 输出与重构前对固定 query 完全一致（rrfScore 逐文档相同）。
- [ ] AC3：`hybridRetrievalEnabled=false` 时仅注册 1 个 RetrievalPath（vector），`hybridSearch` 输出与重构前纯向量模式一致。
- [ ] AC4：新增一个 no-op `StubRetrievalPath`（test-only `@Component`）后 fork 数量变为 3，证明 `paths.size()` 驱动 fork 数（OCP 验证）。
- [ ] AC5：`rrfFusion` 按 `RrfWeighting.SCORE_WEIGHTED`（vector）vs `RANK_ONLY`（bm25）正确选择加权公式，与当前硬编码逻辑等价。
- [ ] AC6：降级行为保持——全部失败抛 `ServiceException`，部分失败返回非失败路结果 + log.warn。
- [ ] AC7：`ScoredDocument` 为顶层 `public record`，`HybridSearchService` 内部不再有同名 private record。

## Dependencies

- **无外部子任务依赖**——Wave 0，三者互不依赖之一。
- 下游依赖方：`ecr-path-c-retrieval` 将创建 `EntityRetrievalPath implements RetrievalPath` 并注册为 `@Component`，依赖本子任务的接口 + `HybridSearchService` 构造注入。

## Out of Scope

- `EntityRetrievalPath`（属 `ecr-path-c-retrieval`）。
- `RagRetrievalProperties` 新增 `entity` 嵌套字段（属 `ecr-path-c-retrieval`）。
- `fusionTopK` 默认值 60→80 变更（这是配置变更，待 Path C 上线时由 `ecr-path-c-retrieval` 连同 `EntityRetrievalProperties` 一起改；本次纯重构不改默认值）。
- 任何实体相关代码（mapper、service、索引）。
- `hybridSearch()` 方法签名变更——保持 `hybridSearch(String queryText, long userId, @Nullable Long teamId)` 不变。

## Open Questions

- **OQ1：RetrievalPath / ScoredDocument 包路径选择**——§10.2 给出两个备选 `agent/service/` vs `rag/retrieval/`。
  - 倾向 `rag/retrieval/`：`HybridDocumentRetriever` 已在此包（是 `HybridSearchService` 的调用方），后续 `EntityRetrievalPath` 也在此包，将检索抽象收敛到 `rag/retrieval/` 符合 CARP 原则；`HybridSearchService` 可留在 `agent/service/`（调用方不变），仅依赖 `rag/retrieval/RetrievalPath` 接口。
  - 反对理由：`HybridSearchService` 本身在 `agent/service/`，接口放在同包更符合"就近原则"。
  - **决定**：实现时根据 `rag/retrieval/` 已有文件密度判断——当前 `rag/retrieval/` 有 5 个文件（含 `HybridDocumentRetriever` 适配器），放入此包可形成检索层内聚。采纳 `rag/retrieval/`。
- **OQ2：适配器如何避免代码复制**——`VectorRetrievalPath` 和 `Bm25RetrievalPath` 需要访问 `VectorStore`/`VectorStoreMapper`/`QueryNormalizer`/`RagRetrievalProperties`。方案：(a) 将 `vectorSearchWithScore`/`bm25Search` 提取为 package-private 方法留在 `HybridSearchService` 内，适配器委托调用；(b) 将检索逻辑完全移入适配器。方案 (a) 改动最小、风险最低。**决定**：方案 (a)，适配器持有对 `HybridSearchService` 的引用（package-private 方法委托），待重构稳定后如果需要进一步解耦再提取。
  - **修正**：方案 (a) 会导致循环依赖（适配器注入 HybridSearchService → HybridSearchService 注入 List<适配器>）。必须采用方案 (b)：将 `VectorStore`/`VectorStoreMapper`/`QueryNormalizer`/`RagRetrievalProperties` 直接注入适配器，检索逻辑从 HybridSearchService 移入适配器。HybridSearchService 保留 `rrfFusion`、降级逻辑和 ScopedTasks 编排。
