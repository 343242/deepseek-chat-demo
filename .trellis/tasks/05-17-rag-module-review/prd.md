# PRD — RAG 模块 Code Review 修复（排除 evaluation）

> 来源：对 RAG 模块 7 个子包、22 个核心文件的 code review（4 CRITICAL / 7 SUGGESTION）

## 背景

对 RAG 模块进行全面 code review，涵盖：retrieval（检索层）、config（配置层）、mapper（数据访问层）、etl（ETL 层）、chunk（分块层）、upload（上传层）、parser（解析层）。排除 evaluation 模块。

整体架构优秀，Pipeline 分层清晰，多租户隔离设计良好。发现 4 个必须修复的问题和 7 个改进建议。

## 审查范围

| 包路径 | 文件数 | 说明 |
|--------|--------|------|
| `rag/retrieval/` | 3 | HybridDocumentRetriever, BailianRerankPostProcessor, MmrDocumentPostProcessor |
| `rag/config/` | 4 | RagRetrievalProperties, RagAdvisorFactory, RagConfig, DocumentProperties |
| `rag/mapper/` | 1 | VectorStoreMapper |
| `rag/etl/` | 1 | FastTrackStrategy |
| `rag/chunk/` | 1 | ParentDocumentPostProcessor |
| `rag/upload/` | 4 | ChunkUploadServiceImpl, PersonalUploadStrategy, OrphanChunkCleaner, BucketResolver |
| `rag/parser/` | 5 | OpenDataLoaderPdfParser, PdfDocumentParser, DocxDocumentParser, PptDocumentParser, ExcelDocumentParser |

## 修复项

### Phase 1: CRITICAL 修复（4 项）

| ID | 文件 | 行号 | 问题 | 修复方案 |
|----|------|------|------|----------|
| C1 | `BailianRerankPostProcessor.java` | 47-51 | WebClient 未配置连接池和超时，高并发下可能连接耗尽 | 配置 Reactor Netty HttpClient，见下方代码 |
| C2 | `BailianRerankPostProcessor.java` | 148 | `Thread.sleep` 阻塞虚拟线程，可能 pin 平台线程 | 改用 `CompletableFuture.delayedExecutor` |
| C3 | `VectorStoreMapper.java` | 96-98 | `batchFetchParents` 使用 `String.formatted()` 构建 SQL，危险模式 | 添加输入校验 + 注释警告 |
| C4 | `ChunkUploadServiceImpl.java` | 7, 16 | 重复导入 `TeamStatusService` | 删除重复导入 |

### Phase 2: SUGGESTION 修复（7 项）

| ID | 文件 | 行号 | 问题 | 修复方案 |
|----|------|------|------|----------|
| S1 | `RagAdvisorFactory.java` | 138-146 | DCL 模式可简化，使用 `AtomicReference` 更清晰 | 改用 `AtomicReference.updateAndGet` |
| S2 | `HybridDocumentRetriever.java` | 131-134 | BM25 失败时静默降级，无完整异常栈 | 添加 DEBUG 级别完整异常日志 |
| S3 | `VectorStoreMapper.java` | 184-188 | `pairwiseCosineDistance` 截断后无标记 | 返回结果中添加 `__truncated__` 标记 |
| S4 | `PptDocumentParser.java` | 204 | GroupShape 递归深度硬编码 | 提取到 `DocumentProperties` 配置 |
| S5 | `ExcelDocumentParser.java` | 341 | StringBuilder 容量预估过大 | 分块输出或降低上限 |
| S6 | `FastTrackStrategy.java` | 178-182 | 异步任务异常处理不完整，无重试机制 | 添加重试或通知机制 |
| S7 | `DocumentProperties.java` | 35-50 | 使用传统 getter/setter | 考虑使用 record + `@ConfigurationProperties` |

## 详细修复方案

### C1: BailianRerankPostProcessor — WebClient 连接池配置

**当前代码（有问题）：**
```java
this.webClient = WebClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("Authorization", "Bearer " + apiKey)
        .defaultHeader("Content-Type", "application/json")
        .build();
```

**修复后：**
```java
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.TcpClient;
import io.netty.channel.ChannelOption;

// 在构造函数中配置
HttpClient httpClient = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)  // 连接超时 5s
        .responseTimeout(Duration.ofSeconds(30))              // 响应超时 30s
        .metrics(true)                                        // 启用指标
        .wiretap(false);                                      // 关闭 wiretap

this.webClient = WebClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader("Authorization", "Bearer " + apiKey)
        .defaultHeader("Content-Type", "application/json")
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
```

**依赖检查：** 确保 `pom.xml` 中有 `reactor-netty-http` 依赖（Spring Boot WebFlux 自带）。

---

### C2: BailianRerankPostProcessor — Thread.sleep 替换

**当前代码：**
```java
try {
    Thread.sleep(backoff);
} catch (InterruptedException ie) {
    Thread.currentThread().interrupt();
    throw new RuntimeException("Interrupted during rerank retry backoff", ie);
}
```

**修复后：**
```java
// 方案 1：使用 CompletableFuture.delayedExecutor（推荐，保持同步调用链）
try {
    CompletableFuture.delayedExecutor(backoff, TimeUnit.MILLISECONDS)
            .execute(() -> {}).get();
} catch (Exception ie) {
    Thread.currentThread().interrupt();
    throw new RuntimeException("Interrupted during rerank retry backoff", ie);
}

// 方案 2：使用 LockSupport.parkNanos（更底层）
long parkNanos = TimeUnit.MILLISECONDS.toNanos(backoff);
long deadline = System.nanoTime() + parkNanos;
while (System.nanoTime() < deadline) {
    LockSupport.parkNanos(deadline - System.nanoTime());
    if (Thread.interrupted()) {
        throw new RuntimeException("Interrupted during rerank retry backoff");
    }
}
```

**推荐方案 1**，代码更简洁。

---

### C3: VectorStoreMapper — SQL 拼接防御

**当前代码：**
```java
String sql = """
        SELECT id, content, metadata
        FROM vector_store
        WHERE metadata->>'parentId' IN (%s)
          AND metadata->>'isParent' = 'true'
        """.formatted(placeholders);
```

**修复后：**
```java
/**
 * 批量回查父文档（Parent-Child 切分策略）
 *
 * @param parentIds 需要回查的父文档 ID 集合（必须为内部生成的安全 ID）
 * @return parentId → Document 的映射
 */
public Map<String, Document> batchFetchParents(Set<String> parentIds) {
    if (parentIds.isEmpty()) {
        return Map.of();
    }

    // 防御性校验：确保 ID 不含 SQL 特殊字符（虽然应为内部生成的 UUID）
    for (String id : parentIds) {
        if (id == null || id.isEmpty() || id.contains("'") || id.contains(";")) {
            throw new IllegalArgumentException("Invalid parent ID: " + id);
        }
    }

    Map<String, Document> result = new HashMap<>();
    String placeholders = String.join(",", Collections.nCopies(parentIds.size(), "?"));
    
    // 使用参数化查询，IN 子句通过 PreparedStatement 参数绑定
    String sql = """
            SELECT id, content, metadata
            FROM vector_store
            WHERE metadata->>'parentId' IN (%s)
              AND metadata->>'isParent' = 'true'
            """.formatted(placeholders);

    // ... 后续代码不变
}
```

---

### C4: ChunkUploadServiceImpl — 删除重复导入

**修复：** 删除第 16 行的重复导入：
```java
// 删除这行
import com.demo.chat.common.team.TeamStatusService;
```

---

### S1: RagAdvisorFactory — DCL 简化

**当前代码：**
```java
private volatile List<...> cachedPostProcessors;

private List<...> getPostProcessors() {
    if (cachedPostProcessors == null) {
        synchronized (this) {
            if (cachedPostProcessors == null) {
                cachedPostProcessors = List.copyOf(buildPostProcessors());
            }
        }
    }
    return cachedPostProcessors;
}
```

**修复后：**
```java
private final AtomicReference<List<...>> cachedPostProcessors = new AtomicReference<>();

private List<...> getPostProcessors() {
    return cachedPostProcessors.updateAndGet(existing -> 
        existing != null ? existing : List.copyOf(buildPostProcessors())
    );
}
```

---

### S2: HybridDocumentRetriever — BM25 失败日志增强

**修复后：**
```java
} catch (Exception e) {
    log.warn("BM25 search failed, falling back to vector-only: {}", e.getMessage());
    log.debug("BM25 search exception detail", e);  // 添加完整异常栈
    return List.of();
}
```

---

### S3: VectorStoreMapper — 截断标记

**修复后：**
```java
public Map<String, Double> pairwiseCosineDistance(List<String> docIds) {
    boolean truncated = false;
    
    if (docIds.size() > MAX_PAIRWISE_DOCS) {
        log.warn("pairwiseCosineDistance: truncating {} docs to {}", 
                docIds.size(), MAX_PAIRWISE_DOCS);
        docIds = docIds.subList(0, MAX_PAIRWISE_DOCS);
        truncated = true;
    }

    // ... 计算逻辑 ...

    if (truncated) {
        result.put("__truncated__", 1.0);  // 标记结果被截断
    }
    
    return result;
}
```

---

### S4: PptDocumentParser — 递归深度配置化

**修复后：**
```java
// DocumentProperties.java 添加配置
private int maxGroupShapeDepth = 10;

public int getMaxGroupShapeDepth() { return maxGroupShapeDepth; }
public void setMaxGroupShapeDepth(int maxGroupShapeDepth) { this.maxGroupShapeDepth = maxGroupShapeDepth; }

// PptDocumentParser.java 使用配置
private final int maxGroupDepth;

public PptDocumentParser(DocumentProperties documentProperties) {
    this.maxGroupDepth = documentProperties.getMaxGroupShapeDepth();
}

private void processGroupShape(XSLFGroupShape group, StringBuilder textBuffer, int depth, String fileName) {
    if (depth > maxGroupDepth) {
        log.warn("GroupShape recursion depth exceeded {} in file={}, stopping", maxGroupDepth, fileName);
        return;
    }
    // ...
}
```

---

## 亮点（做得好的地方）

1. **Pipeline 分层清晰** — QueryNormalize → RewriteQuery → HybridRetrieve → MMR → Rerank → ParentDocument
2. **多租户隔离设计优秀** — userId/teamId 过滤，RagAdvisorFactory 按请求创建
3. **MMR + Rerank 顺序正确** — 先去冗余再精排，避免 Rerank 浪费算力
4. **FastTrack 策略巧妙** — 小文档 BM25 先行，异步向量化补齐
5. **Parser 策略模式** — 新增格式只需实现 DocumentParser 接口
6. **pairwiseCosineDistance 防御性截断** — O(n²) SQL 有上限保护

## 约束

- 每个 Phase 独立 commit
- 编译通过后才能提交
- 不 push，用户手动 push
- 优先修复 C1、C2（虚拟线程兼容性）

## 验证清单

- [ ] C1: WebClient 连接池配置
- [ ] C2: Thread.sleep → CompletableFuture.delayedExecutor
- [ ] C3: SQL 拼接防御性校验
- [ ] C4: 删除重复导入
- [ ] S1-S7: 按需修复
- [ ] 所有修改通过 `mvn clean compile`
- [ ] 相关单元测试通过（如有）
