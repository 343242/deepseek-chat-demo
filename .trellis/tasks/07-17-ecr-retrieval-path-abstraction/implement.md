# Implementation Plan — RetrievalPath 接口 + HybridSearchService 重构

## 前置阅读

- `.trellis/spec/backend/code-review-checklist.md`（review 维度）
- `.trellis/spec/guides/cross-layer-thinking-guide.md`（跨层边界）

## 实施清单

### Step 1：提取 ScoredDocument 到顶层 public record

- [ ] 在 `src/main/java/com/smart/rag/rag/retrieval/` 创建 `ScoredDocument.java`：
  ```java
  package com.smart.rag.rag.retrieval;

  import org.springframework.ai.document.Document;

  public record ScoredDocument(Document doc, int rank, double score) {}
  ```
- [ ] 删除 `HybridSearchService` 第 254 行的 `private record ScoredDocument`。
- [ ] `HybridSearchService` 添加 `import com.smart.rag.rag.retrieval.ScoredDocument;`。
- [ ] 编译验证：`./mvnw compile -pl . -q` 无错误。

### Step 2：创建 RetrievalPath 接口

- [ ] 在 `src/main/java/com/smart/rag/rag/retrieval/` 创建 `RetrievalPath.java`：
  ```java
  package com.smart.rag.rag.retrieval;

  import org.jspecify.annotations.Nullable;
  import java.util.List;

  public interface RetrievalPath {
      String name();
      List<ScoredDocument> search(String query, long userId, @Nullable Long teamId);
      RrfWeighting rrfWeighting();

      enum RrfWeighting { SCORE_WEIGHTED, RANK_ONLY }
  }
  ```
- [ ] 编译验证。

### Step 3：创建 VectorRetrievalPath 适配器

- [ ] 在 `src/main/java/com/smart/rag/rag/retrieval/` 创建 `VectorRetrievalPath.java`。
- [ ] `@Component`，注入 `VectorStore` + `RagRetrievalProperties`。
- [ ] 将 `HybridSearchService.vectorSearchWithScore()`（第 159-169 行）的逻辑搬迁至此：
  - 调用 `vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(topK).similarityThreshold(properties.similarityThreshold()).filterExpression(filter).build())`。
  - filter 逻辑：`teamId != null ? eq("teamId", teamId) : eq("userId", userId)`。
  - rank 从 1 开始编号，score 取 `doc.getScore()`（null → 0.5）。
- [ ] `name()` 返回 `"vector-search"`。
- [ ] `rrfWeighting()` 返回 `RrfWeighting.SCORE_WEIGHTED`。
- [ ] `search()` 内部需要 topK 参数——从 `properties.vectorTopK()` 获取。
- [ ] 编译验证。

### Step 4：创建 Bm25RetrievalPath 适配器

- [ ] 在 `src/main/java/com/smart/rag/rag/retrieval/` 创建 `Bm25RetrievalPath.java`。
- [ ] `@Component` + `@ConditionalOnProperty(prefix = "app.rag", name = "hybridRetrievalEnabled", havingValue = "true")`。
- [ ] 注入 `VectorStoreMapper` + `QueryNormalizer` + `RagRetrievalProperties`。
- [ ] 将 `HybridSearchService.bm25Search()`（第 173-192 行）的逻辑搬迁至此：
  - 调用 `queryNormalizer.sanitizeForTsQuery(query)`。
  - 空值检查 `sanitized.isBlank()` → `List.of()`。
  - isolationField/isolationValue 根据 teamId 决定。
  - 调用 `vectorStoreMapper.bm25Search(ftsConfig, sanitized, isolationField, isolationValue, topK)`。
  - rank 从 1 开始编号，score 固定 `0.0`。
- [ ] `name()` 返回 `"bm25-search"`。
- [ ] `rrfWeighting()` 返回 `RrfWeighting.RANK_ONLY`。
- [ ] `search()` 内部 topK 从 `properties.bm25TopK()` 获取。
- [ ] 编译验证。

### Step 5：重构 HybridSearchService

- [ ] 修改构造器：
  - 移除 `VectorStore vectorStore`、`VectorStoreMapper vectorStoreMapper` 参数。
  - 新增 `List<RetrievalPath> paths` 参数。
  - 保留 `RagRetrievalProperties properties`、`QueryNormalizer queryNormalizer`、`ScopedTasks scopedTasks`。
  - 保留双构造器模式（Executor 版本 + ScopedTasks 直注版本），测试兼容。
  ```java
  public HybridSearchService(List<RetrievalPath> paths,
                             RagRetrievalProperties properties,
                             QueryNormalizer queryNormalizer,
                             @Qualifier("ragSearchExecutor") Executor searchExecutor) {
      this(paths, properties, queryNormalizer, new DefaultScopedTasks());
  }

  @Autowired
  public HybridSearchService(List<RetrievalPath> paths,
                             RagRetrievalProperties properties,
                             QueryNormalizer queryNormalizer,
                             ScopedTasks scopedTasks) {
      this.paths = List.copyOf(paths);  // 防御性拷贝
      this.properties = properties;
      this.queryNormalizer = queryNormalizer;
      this.scopedTasks = scopedTasks;
  }
  ```
- [ ] 移除字段：`vectorStore`、`vectorStoreMapper`。
- [ ] 移除方法：`vectorSearch()`、`vectorSearchOrThrow()`、`vectorSearchWithScore()`、`bm25Search()`（逻辑已搬迁到适配器）。
- [ ] 重构 `hybridSearch()` 方法体：
  - 移除 `hybridRetrievalEnabled` 判断（条件注册已由 `@ConditionalOnProperty` 处理）。
  - `normalized = queryNormalizer.normalize(queryText)` 保留。
  - 遍历 `paths` 做 fork：
    ```java
    Map<RetrievalPath, Subtask<List<ScoredDocument>>> tasks = new LinkedHashMap<>();
    for (RetrievalPath path : paths) {
        tasks.put(path, scope.fork(path.name(), () -> path.search(normalized, userId, teamId)));
    }
    scope.join();
    ```
  - 降级逻辑：统计失败数，全部失败抛异常，部分失败 warn。
  - 调用 `rrfFusion(tasks)`。
- [ ] 重构 `rrfFusion()`：
  - 签名改为 `private List<Document> rrfFusion(Map<RetrievalPath, Subtask<List<ScoredDocument>>> tasks)`。
  - 内部遍历每个 entry，按 `path.rrfWeighting()` 选择加权/纯排名（见 design.md 第 6 节）。
  - 保留 `scores`/`docMap` + 排序 + `limit(fusionTopK)` + `rrfScore` metadata 写入。
- [ ] 修改 `taskResultOrEmpty()` 签名不变（已经接受 `Subtask<List<ScoredDocument>>`），保持。
- [ ] **降级错误消息保留**："向量检索和 BM25 检索均不可用"（第 113 行）——AC1 要求测试不改。
- [ ] 编译验证。

### Step 6：运行既有测试（AC1 验证）

- [ ] `./mvnw test -Dtest="com.smart.rag.rag.retrieval.HybridDocumentRetrieverTest" -pl .`
- **关键**：`HybridDocumentRetrieverTest` 的 `createRetriever()` 方法（第 90-94 行）当前构造 `new HybridSearchService(vectorStore, vectorStoreMapper, props, queryNormalizer, executor)`。重构后此签名不存在。

**迁移策略（二选一，见 OQ2）**：

**方案 A（推荐，AC1 严格通过）**：提供向后兼容构造器重载（deprecated）：
```java
@Deprecated // 仅测试兼容，production 使用 List<RetrievalPath> 构造器
public HybridSearchService(VectorStore vectorStore,
                           VectorStoreMapper vectorStoreMapper,
                           RagRetrievalProperties properties,
                           QueryNormalizer queryNormalizer,
                           Executor searchExecutor) {
    this(buildPaths(vectorStore, vectorStoreMapper, properties),
         properties, queryNormalizer, new DefaultScopedTasks());
}
```
其中 `buildPaths` 是 static 工厂方法，根据 `properties.hybridRetrievalEnabled()` 构造 `VectorRetrievalPath` ± `Bm25RetrievalPath`（用传入的 `VectorStore`/`VectorStoreMapper` 构造适配器实例，而非从 Spring 容器获取）。

**方案 B**：修改测试以使用新构造器。违反 AC1 但更干净。

**决定**：方案 A——保留兼容构造器（`@Deprecated`），让既有测试零修改通过。后续单独 PR 删除兼容构造器 + 更新测试。

- [ ] 实现方案 A 的 `buildPaths` 工厂方法。
- [ ] 同理为 `ScopedTasks` 版构造器提供兼容重载。
- [ ] 运行测试，确认 17 个用例全绿。

### Step 7：添加 OCP 回归测试（AC4）

- [ ] 在 `src/test/java/com/smart/rag/rag/retrieval/` 创建 `HybridSearchServiceRetrievalPathTest.java`（新测试文件，不改既有测试）。
- [ ] 测试：构造 `HybridSearchService` 传入 `[vectorPath, bm25Path, stubPath]`（3 个 mock RetrievalPath），调用 `hybridSearch`，验证 fork 数量 = 3（通过 mock `ScopedTasks` 的 `fork` 调用次数验证）。
- [ ] 测试：构造 `[vectorPath, bm25Path]`（2 个），验证 fork 数量 = 2。
- [ ] 测试：构造 `[vectorPath]`（1 个），验证 fork 数量 = 1 且输出正确。
- [ ] 测试：`rrfFusion` 按 `SCORE_WEIGHTED` vs `RANK_ONLY` 正确选择公式（AC5）。
- [ ] 运行新测试 + 既有测试。

### Step 8：运行全量测试（回归验证）

- [ ] `./mvnw test -Pdefault` 全量测试绿色。
- [ ] 特别关注 `RagRetrievalPropertiesTest`（`fusionTopK` 默认 60 验证——本次不改默认值）。

## Validation Commands

```bash
# 编译
./mvnw compile -pl . -q

# 既有测试（AC1）
./mvnw test -Dtest="com.smart.rag.rag.retrieval.HybridDocumentRetrieverTest" -pl .

# 新增 OCP 回归测试
./mvnw test -Dtest="com.smart.rag.rag.retrieval.HybridSearchServiceRetrievalPathTest" -pl .

# 全量测试
./mvnw test -Pdefault
```

## Review Gates

- [ ] `code-review-checklist.md` 第 1 维（OCP）：`HybridSearchService` 无硬编码路径名/路径数量。
- [ ] `code-review-checklist.md` 第 2 维（循环依赖）：`mvn dependency:tree` 确认无循环。
- [ ] `code-review-checklist.md` 第 5 维（并发安全）：`ScopedTasks` fork/join 使用与重构前一致。
- [ ] `cross-layer-thinking-guide.md`：`RetrievalPath.search()` 返回 `ScoredDocument` → `rrfFusion()` 消费 `ScoredDocument`，数据流在同一 Service 内，无跨层问题。

## Rollback Points

- 本子任务为纯重构，不改数据层、不改配置默认值。
- 回滚 = revert 本子任务的 commit（兼容构造器可后续清理，不影响回滚）。
- `entity.enabled=false` 运行时回滚对本次无关（本次不涉及实体代码）。

## 文件清单

| 操作 | 文件路径 | 说明 |
|---|---|---|
| 新建 | `src/main/java/com/smart/rag/rag/retrieval/ScoredDocument.java` | 顶层 public record |
| 新建 | `src/main/java/com/smart/rag/rag/retrieval/RetrievalPath.java` | 接口 + 枚举 |
| 新建 | `src/main/java/com/smart/rag/rag/retrieval/VectorRetrievalPath.java` | @Component 适配器 |
| 新建 | `src/main/java/com/smart/rag/rag/retrieval/Bm25RetrievalPath.java` | @Component + @ConditionalOnProperty 适配器 |
| 修改 | `src/main/java/com/smart/rag/agent/service/HybridSearchService.java` | 构造器 + hybridSearch + rrfFusion 重构 |
| 新建 | `src/test/java/com/smart/rag/rag/retrieval/HybridSearchServiceRetrievalPathTest.java` | OCP 回归测试 |
