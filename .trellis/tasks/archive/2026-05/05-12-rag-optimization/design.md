# RAG 模块优化方案

## 审计范围

完整审查了 RAG 模块 56 个 Java 文件，覆盖：
- ETL 流水线（Extract → Transform → Load）
- 检索链（查询改写 → 混合检索 → Rerank → MMR → 父文档回查）
- Embedding（DashScope 自实现）
- 文档管理（上传/查询/删除）
- 线程池/策略路由

---

## 发现的问题（按严重程度排序）

### P0 — 功能缺陷

#### 1. 单文件上传接口同步阻塞 ETL
**位置**: `DocumentApplicationServiceImpl.upload()`
**问题**: `etlDispatchService.executeSingle()` 同步执行完整 ETL，上传接口等到 ETL 完成才返回。单文件场景也要等 Extract + Transform（Embedding API）+ Load，典型延迟 10-30 秒。
**对比**: `uploadBatch()` 已经是异步的（dispatch → 返回 PROCESSING）。
**修复**: upload() 也应异步执行 ETL，上传后立即返回 PROCESSING。

#### 2. FastTrack writeBm25Row 没有 content_tsv 触发器参与
**位置**: `FastTrackStrategy.writeBm25Row()`
**问题**: 使用 `jdbcTemplate.update(INSERT INTO vector_store ...)` 直接插入，content_tsv 由数据库触发器填充，但插入时 embedding=NULL。如果 PgVectorStore 有 afterPropertiesSet 建表逻辑且在该行之后执行，可能存在竞争。当前实际运行正常（触发器在 DB 层面，不受 Java 层影响），但需确认。
**严重性**: 低，实际运行正常。记录为 awareness。

#### 3. ParentDocumentPostProcessor 逐个 parentId 查询向量库（N+1）
**位置**: `ParentDocumentPostProcessor.fetchParentDocuments()`
**问题**: 注释里也写了"如果性能成为瓶颈，可考虑加缓存"。每次检索可能命中 5-10 个子块，每个子块触发一次 `vectorStore.similaritySearch()`，每次都是向量查询（只是用 filter 做精确匹配）。
**影响**: 检索链多了 5-10 次向量库查询，显著增加 P95 延迟。
**修复**: 用 JdbcTemplate 直接按 parentId 批量查询（向量库底层是 PG，可以直接 SQL）。

### P1 — 性能问题

#### 4. RagAdvisorFactory 每次请求创建新 Advisor 实例
**位置**: `RagAdvisorFactory.create()`
**问题**: 每次聊天请求都 `new RetrievalAugmentationAdvisor()` + `new HybridDocumentRetriever()`，对象包含 QueryTransformer 链、PostProcessor 链。这些组件都是无状态的（除了 userId），但每次请求都重建。
**修复**: 缓存 Advisor（以 userId 为 key），或使用 Object Pool。实际上 userId 数量有限，可以用 ConcurrentHashMap 缓存。

#### 5. DashScopeEmbeddingModel 每次 API 调用 30s 硬编码超时
**位置**: `DashScopeEmbeddingModel.API_TIMEOUT`
**问题**: 30s 超时不可配置。DashScope Embedding API 正常响应 < 1s，但网络抖动时 30s 太长。大批量 ETL 时，一个慢调用阻塞整个批次。
**修复**: 超时时间提取到 DashScopeEmbeddingProperties，默认 10s。

#### 6. MinioFileStorageService.download() 全量加载到内存
**位置**: `MinioFileStorageService.download()`
**问题**: `is.readAllBytes()` 将整个文件加载到堆内存。大文件（上限 50MB）直接吃掉堆空间。多文件并发 ETL 时可能 OOM。
**修复**: 返回 InputStream 而非 ByteArrayResource，或使用临时文件。但需要改 Extractor 接口，影响面较大。中期优化。

#### 7. BM25 sanitizeQuery 过于激进
**位置**: `HybridDocumentRetriever.sanitizeQuery()`
**问题**: 正则 `[^\\p{IsHan}a-zA-Z0-9\\s，。！？、；：\"''（）\\-]` 移除了大量有效字符，如英文标点（逗号、句号）、数字符号、数学运算符等。对英文查询可能丢失重要信息。
**修复**: 放宽过滤规则，保留更多有效字符，或改用参数化查询防止注入（当前已是 `?` 占位符，sanitize 主要是防 plainto_tsquery 异常）。

### P2 — 健壮性问题

#### 8. ETL 失败无重试机制
**位置**: `StandardStrategy` / `FastTrackStrategy` 各阶段
**问题**: Extract/Transform/Load 任一阶段失败直接标记 FAILED，无自动重试。DashScope API 限流、MinIO 瞬断等瞬时错误也不重试。
**修复**: 
- 简单方案：提供 `/api/documents/{id}/retry` 手动重试接口
- 完整方案：可重试异常（IOException、HTTP 429/503）自动重试 2-3 次，指数退避

#### 9. uploadBatch 的状态轮询不完整
**位置**: `DocumentApplicationServiceImpl.uploadBatch()`
**问题**: dispatch 异步后，立即查询 DB 状态。但 dispatch 内部是同步执行的（StandardStrategy/FastTrackStrategy.execute() 是同步方法，只是用线程池并行），所以查询时 ETL 可能已经完成了。这个"立即查询"实际上大部分时候能拿到最终状态，但如果线程池队列满（CallerRunsPolicy），则当前线程阻塞执行 ETL，此时查询才真正有意义。
**修复**: 去掉 dispatch 后的立即查询，统一返回 PROCESSING，让前端通过 `/status` 接口轮询。

#### 10. FastTrack BM25 写入使用新 ObjectMapper 实例
**位置**: `FastTrackStrategy.writeBm25Row()` 中 `new ObjectMapper()`
**问题**: 每次 BM25 写入创建新的 ObjectMapper，浪费资源。应注入共享实例。
**严重性**: 极低，但不符合最佳实践。

#### 11. HybridDocumentRetriever 内 ObjectMapper 也是 static final
**位置**: `HybridDocumentRetriever.OBJECT_MAPPER`
**问题**: 同上，虽然是 static final 复用了，但与 Spring 容器内的 ObjectMapper 不共享配置（如日期格式、特性开关）。应注入共享 ObjectMapper。
**严重性**: 低。

#### 12. DocumentController.getStatus() 与 getById() 完全重复
**位置**: `DocumentController` 第 64-70 行
**问题**: `GET /{id}/status` 和 `GET /{id}` 调用的是同一个 `getById()`，返回完全相同的数据。
**修复**: 去掉 `getStatus()`，让前端统一用 `GET /{id}` 查状态。

### P3 — 代码质量

#### 13. EtlPipelineServiceImpl.execute() 直接 throw UnsupportedOperationException
**位置**: `EtlPipelineServiceImpl.execute()`
**问题**: 接口方法实现为 throw，说明接口设计有问题——要么删掉这个方法，要么 EtlPipelineServiceImpl 不应该实现这个接口。
**修复**: 如果确认不再使用 EtlPipelineService 接口的 execute()，重构接口或删除。

#### 14. EtlTaskExecutorBridge 注册为 Bean 但几乎没被使用
**位置**: `EtlTaskExecutorBridge`
**问题**: StandardStrategy 和 FastTrackStrategy 直接注入 `etlIoExecutor` / `etlCpuExecutor`，没有用 Bridge。Bridge 只是个中间层，增加了理解成本。
**修复**: 确认是否有其他使用者。如果没有，考虑移除。

#### 15. FastTrackStrategy 每次创建 ObjectMapper
**位置**: `FastTrackStrategy.writeBm25Row()`
**问题**: `new com.fasterxml.jackson.databind.ObjectMapper()` 每次调用都创建新实例。

#### 16. 缺少 ETL 进度通知
**问题**: 前端只能通过轮询 `/status` 获取处理进度。大文件 ETL 可能需要 30 秒以上，用户体验差。
**修复**: 可通过 SSE 推送 ETL 状态变更（已有 SseEmitter 基础设施）。属于体验优化。

---

## 优化方案（按优先级）

### Phase 1: P0 修复（1-2 小时）

| # | 优化项 | 改动范围 |
|---|--------|---------|
| 1 | **单文件上传异步化** — upload() 走 dispatch 异步，立即返回 PROCESSING | `DocumentApplicationServiceImpl` |
| 2 | **ParentDocumentPostProcessor 批量查询** — 用 JdbcTemplate 按 parentId 批量查询，替代 N+1 向量查询 | `ParentDocumentPostProcessor` |
| 3 | **去重 getStatus 接口** — 前端统一用 `GET /{id}` | `DocumentController` |

### Phase 2: P1 性能优化（2-3 小时）

| # | 优化项 | 改动范围 |
|---|--------|---------|
| 4 | **RagAdvisorFactory 缓存** — ConcurrentHashMap<userId, Advisor> 缓存，避免每次请求重建 | `RagAdvisorFactory` |
| 5 | **Embedding 超时可配置** — 提取到 Properties，默认 10s | `DashScopeEmbeddingProperties` + `DashScopeEmbeddingModel` |
| 6 | **ObjectMapper 统一注入** — FastTrackStrategy + HybridDocumentRetriever 注入共享 ObjectMapper | 2 个类 |
| 7 | **uploadBatch 去掉立即查询** — 统一返回 PROCESSING | `DocumentApplicationServiceImpl` |

### Phase 3: P2 健壮性（2-3 小时）

| # | 优化项 | 改动范围 |
|---|--------|---------|
| 8 | **ETL 失败重试** — 新增 `/api/documents/{id}/retry` 接口 + dispatch 重试逻辑 | Controller + Service |
| 9 | **Embedding API 限流 backoff** — 429/503 时指数退避重试 | `DashScopeEmbeddingModel` |
| 10 | **清理遗留代码** — EtlPipelineService 接口重构、EtlTaskExecutorBridge 评估 | 多文件 |

### Phase 4: 体验优化（可选）

| # | 优化项 | 改动范围 |
|---|--------|---------|
| 11 | **ETL 状态 SSE 推送** — 文档处理进度实时通知前端 | Controller + Service |
| 12 | **MinIO 流式下载** — 避免 50MB 全量内存加载 | `MinioFileStorageService` + `Extractor` |

---

## 不做的事

| 项目 | 原因 |
|------|------|
| 引入消息队列 | 单机部署，线程池够用；未来需要时用 Redis Stream（已有 Redis） |
| 向量缓存（Embedding Cache） | 查询场景每次 query 不同，缓存命中率低；入库场景只执行一次 |
| 混合检索并行化 | 当前向量+BM25 已经是顺序执行，但两者都是毫秒级，并行化的收益低于复杂度成本 |
