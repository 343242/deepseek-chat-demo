# RAG 模块六维深度代码审查报告

**审查日期**: 2026-05-18
**审查分支**: eval-rag-dev
**审查范围**: `com.demo.chat.rag` 全部 113 个 Java 源文件
**审查维度**: 资源泄漏 / 边界条件 / 并发安全 / 性能陷阱 / 异常处理 / 内存泄漏

---

## 统计总览

| 级别 | 数量 | 说明 |
|------|------|------|
| **BLOCKER** | 3 | 必然导致数据丢失/资源耗尽/系统崩溃 |
| **HIGH** | 14 | 高概率导致生产事故 |
| **MEDIUM** | 32 | 高负载或边界场景下出问题 |
| **LOW** | 17 | 代码质量改进 |

---

## 🔴 BLOCKER (3个)

### [B-1] MinioFileStorageService.download() 将整个文件加载到 JVM 堆内存
- **文件**: `service/impl/MinioFileStorageService.java`
- **维度**: 内存泄漏 / 性能陷阱
- **问题描述**: `download()` 使用 `is.readAllBytes()` 将 MinIO 对象全部内容读入 `byte[]`，包装为 `ByteArrayResource`。500MB 的 PPTX 将直接 OOM。
- **修复**: 使用流式返回（`InputStreamResource` 或临时文件缓冲），或通过预签名 URL 让调用方直接下载。

### [B-2] ChunkUploadServiceImpl.complete() 与 autoMerge 存在并发竞态
- **文件**: `upload/ChunkUploadServiceImpl.java`
- **维度**: 并发安全
- **问题描述**: `uploadChunk()` 在最后一个分片到达时通过 Lua 脚本原子设置 `__merging` 标记并异步 `performMerge()`。但 `complete()` 方法**不检查 `__merging` 标记**直接调用 `performMerge()`。若用户在自动合并进行中手动调用 `complete()`，两个线程同时 `composeObject` 合并相同分片，导致数据损坏。
- **修复**: `complete()` 开头检查 `__merging` 标记，正在合并时返回等待或拒绝。

### [B-3] EncodingDetector 重复 import 导致编译失败
- **文件**: `parser/EncodingDetector.java`
- **维度**: 异常处理
- **问题描述**: `import java.nio.charset.UnsupportedCharsetException` 在第 9 行和第 10 行重复，Java 编译器拒绝编译。
- **修复**: 删除重复的 import 行。

---

## 🟠 HIGH (14个)

### [H-1] ParentDocumentPostProcessor rescoring 排序键错误 — Parent Rescoring 完全失效
- **文件**: `chunk/ParentDocumentPostProcessor.java`
- **维度**: 边界条件
- **问题描述**: `parentScoreMap` 的 key 是 metadata 中的 `parentId`，但排序时使用 `doc.getId()`（vector_store UUID 主键）查找。二者**永远不相等**，所有父文档回退到 `DEFAULT_SCORE (0.5)`，排序退化为插入序。H-RAG 论文中标注的 **"最大收益因素 (+0.0197 nDCG@5)" 完全失效**。
- **修复**: 排序查找键改为 `doc.getMetadata().get("parentId")`。

### [H-2] ChunkStrategyFactory 空策略列表导致启动崩溃
- **文件**: `chunk/ChunkStrategyFactory.java`
- **维度**: 边界条件
- **问题描述**: `strategies.get(0)` 在无 ChunkStrategy Bean 注册时抛 `IndexOutOfBoundsException`，Spring 容器启动失败。
- **修复**: 先检查 `strategies.isEmpty()`，抛明确 `IllegalStateException`。

### [H-3] RagAdvisorFactory userId null → "null" 字符串隔离过滤器
- **文件**: `config/RagAdvisorFactory.java`
- **维度**: 边界条件
- **问题描述**: `userId` 为 null 时，`String.valueOf(userId)` 返回 `"null"` 字符串，导致查询条件变为 `userId == "null"`，用户看不到任何文档且无错误提示。
- **修复**: null 检查 + 明确异常。

### [H-4] MinioProperties 硬编码默认凭证
- **文件**: `config/MinioProperties.java`
- **维度**: 安全（资源泄漏范畴）
- **问题描述**: `accessKey` / `secretKey` 的 `defaultValue` 为 `"minioadmin"/"minioadmin"`。如果 application.yml 漏配，生产环境使用默认凭证。
- **修复**: 去掉默认值，启动时强制要求配置。

### [H-5] PlainTextDocumentParser 全量加载大文件无防护上限
- **文件**: `parser/PlainTextDocumentParser.java`
- **维度**: 内存泄漏 / 边界条件
- **问题描述**: `is.readAllBytes()` 全量加载 + `content.split()` 二次拷贝。500MB 日志文件 → 1.5GB+ 内存。
- **修复**: 增加文件大小上限检查（如 50MB），超大文件拒绝或改用流式。

### [H-6] EncodingDetector.detectAndTranscode() 无条件全量加载
- **文件**: `parser/EncodingDetector.java`
- **维度**: 内存泄漏 / 性能陷阱
- **问题描述**: UTF-8 兼容文件也做完整拷贝（`readAllBytes()` → `NamedByteArrayResource`），200MB 文件多次全量拷贝。
- **修复**: UTF-8 兼容文件直接返回原始 Resource，不做拷贝。

### [H-7] DocumentExtractor MinIO 下载的 Resource 未被关闭
- **文件**: `etl/DocumentExtractor.java`
- **维度**: 资源泄漏
- **问题描述**: `fileStorageService.download()` 返回的 Resource 持有底层 HTTP 连接，传递给 Parser 后无统一关闭点。Parser 未关闭 InputStream → 连接池泄漏。
- **修复**: 使用 try-with-resources 或 try-finally 显式关闭 Resource。

### [H-8] 向量检索异常直接向上传播，无降级处理
- **文件**: `retrieval/HybridDocumentRetriever.java`
- **维度**: 异常处理
- **问题描述**: `vectorSearch()` 直接调用 `vectorStore.similaritySearch()` 无 try-catch。pgvector 连接池耗尽/超时 → 异常直接抛出 → RAG 管道中断。对比 `bm25Search()` 已有完善捕获 + 降级。
- **修复**: 添加 try-catch，向量检索失败时降级为 BM25-only 或空列表。

### [H-9] MmrDocumentPostProcessor pairwiseCosineDistance 返回值 NPE
- **文件**: `retrieval/MmrDocumentPostProcessor.java`
- **维度**: 边界条件
- **问题描述**: `vectorStoreMapper.pairwiseCosineDistance(docIds)` 返回值直接使用，无 null 检查。Mapper 异常返回 null → 后续循环 NPE → RAG 后处理管道崩溃。
- **修复**: null 检查 + 异常降级为空 Map。

### [H-10] DocumentSupersedeService.pendingSupersede 无上限增长
- **文件**: `service/impl/DocumentSupersedeService.java`
- **维度**: 内存泄漏
- **问题描述**: `ConcurrentHashMap<Long, Long>` 通过 `linkVersion` 插入，`onEtlCompleted` 移除。若 ETL 永远不完成（文件损坏/线程池拒绝），条目永驻内存。
- **修复**: 使用 Caffeine Cache 设置 TTL，或添加定时清理。

### [H-11] DocumentApplicationServiceImpl.getHistory() 无分页
- **文件**: `service/impl/DocumentApplicationServiceImpl.java`
- **维度**: 性能陷阱
- **问题描述**: `selectList` 无 LIMIT，文档被替换数十次后全量返回。
- **修复**: 添加分页参数。

### [H-12] ChunkUploadServiceImpl.validateFileSize() 硬编码 50MB
- **文件**: `upload/ChunkUploadServiceImpl.java`
- **维度**: 边界条件
- **问题描述**: 硬编码 `DataSize.parse("50MB")`，与 `DocumentValidator` 的配置项不一致。
- **修复**: 注入 `DocumentProperties`，统一配置。

### [H-13] EvaluationExecutionService.executeRun() 串行执行 — 并发配置未生效
- **文件**: `evaluation/runner/EvaluationExecutionService.java`
- **维度**: 性能陷阱
- **问题描述**: for 循环逐条执行评估，`concurrency` 配置未使用。100 条数据集 × (改写 + 生成 + 4 Judge) → 数小时。
- **修复**: 使用 `CompletableFuture` + `concurrency` 并发度并行执行。

### [H-14] DashScopeEmbeddingApi.call() 响应体未关闭
- **文件**: `embedding/DashScopeEmbeddingApi.java`
- **维度**: 资源泄漏
- **问题描述**: HTTP 响应体 `body()` 未在 finally 中关闭，网络异常时连接泄漏。高并发 embedding 调用 → HTTP 连接池耗尽。
- **修复**: 使用 try-with-resources 包裹 Response。

---

## 🟡 MEDIUM (32个)

### 资源泄漏 (3个)

| # | 文件 | 问题 |
|---|------|------|
| M-1 | `parser/OpenDataLoaderPdfParser.java` | InputStream 未 try-with-resources，异常时泄漏 |
| M-2 | `evaluation/runner/PipelineInstrumenter.java` | 资源生命周期管理不明确 |
| M-3 | `service/impl/EtlPipelineServiceImpl.java` | ETL 执行中异常可能导致资源未释放 |

### 边界条件 (10个)

| # | 文件 | 问题 |
|---|------|------|
| M-4 | `chunk/ParentChildChunkStrategy.java` | null documents 参数 → NPE |
| M-5 | `chunk/StructureAwareChunkStrategy.java` | null documents 参数 → NPE |
| M-6 | `chunk/TokenChunkStrategy.java` | null documents 参数 → NPE |
| M-7 | `config/DocumentProperties.java` | chunkSize/overlap 无 `@Min`/`@Positive` 校验，可为 0 或负数 |
| M-8 | `retrieval/BailianRerankPostProcessor.java` | Rerank 返回空 results 列表时未降级到原始文档 |
| M-9 | `retrieval/HybridDocumentRetriever.java` | RRF 融合中 `doc.getId()` 可能为 null → NPE |
| M-10 | `upload/DefaultChunkSizeStrategy.java` | while 循环无上限保护 |
| M-11 | `controller/DocumentController.java` | 未校验空文件/空文件名 |
| M-12 | `config/EtlFastTrackProperties.java` | DataSize.parse 无错误处理 |
| M-13 | `config/RagRetrievalProperties.java` | 数值参数无范围校验 |

### 并发安全 (4个)

| # | 文件 | 问题 |
|---|------|------|
| M-14 | `config/RagAdvisorFactory.java` | PostProcessor 缓存 volatile 竞态条件 |
| M-15 | `etl/EtlStatusManager.java` | `failDocument()` 事务异常只记日志，调用方无感知 |
| M-16 | `etl/StandardStrategy.java` | loadAll 多线程共享 chunkMap 中的同一个 List 引用 |
| M-17 | `etl/FastTrackStrategy.java` | CompletableFuture.supplyAsync 对所有 candidate 无并发限制 |

### 性能陷阱 (8个)

| # | 文件 | 问题 |
|---|------|------|
| M-18 | `mapper/RagDocumentMapper.java` | findStaleSupersededTargets() 无界 SELECT |
| M-19 | `evaluation/dataset/DatasetGenerator.java` | ORDER BY RANDOM() 在大表上全表扫描 |
| M-20 | `upload/OrphanChunkCleaner.java` | 扫描所有 Bucket 含团队 Bucket |
| M-21 | `etl/FastTrackStrategy.java` | 大文档 text joining 产生大字符串 |
| M-22 | `config/MinioConfig.java` | 无连接池/超时配置 |
| M-23 | `evaluation/dataset/DatasetExporter.java` | 大数据集导出可能 OOM |
| M-24 | `service/impl/EtlPipelineServiceImpl.java` | ETL 结果全量加载无分页 |
| M-25 | `mapper/VectorStoreMapper.java` | parseMetadata 泛化异常捕获 |

### 异常处理 (4个)

| # | 文件 | 问题 |
|---|------|------|
| M-26 | `etl/FastTrackStrategy.java` | EtlResult.failed(e.getMessage()) 丢失原始堆栈 |
| M-27 | `evaluation/judge/LlmJudgeImpl.java` | 重试无退避策略，429 场景全部失败 |
| M-28 | `mapper/VectorStoreMapper.java` | insertFastTrackRow 泛化 RuntimeException 包装 |
| M-29 | `etl/EtlExecutorConfig.java` | awaitTerminationSeconds=120，长 ETL 任务中断后状态不一致 |

### 内存泄漏 (3个)

| # | 文件 | 问题 |
|---|------|------|
| M-30 | `retrieval/MmrDocumentPostProcessor.java` | 距离矩阵缓存无大小限制 |
| M-31 | `evaluation/result/EvaluationResultRepository.java` | 评估结果无限制存储 |
| M-32 | `upload/ChunkUploadServiceImpl.java` | Redis 分片元数据无 TTL，失败上传残留 |

---

## 🟢 LOW (17个)

| # | 文件 | 维度 | 问题 |
|---|------|------|------|
| L-1 | `controller/DocumentController.java` | 代码质量 | replaceDocumentId 分支代码重复 |
| L-2 | `config/RagAdvisorFactory.java` | 死代码 | ObjectMapper 注入但未使用 |
| L-3 | `chunk/ChunkStrategyFactory.java` | 并发安全 | availableStrategies() 返回可变视图 |
| L-4 | `config/RagAdvisorFactory.java` | 缓存 | PostProcessor 缓存不处理运行时配置变更 |
| L-5 | `retrieval/BailianRerankPostProcessor.java` | 性能 | 相似度阈值比较使用浮点等值 |
| L-6 | `retrieval/QueryNormalizer.java` | 边界 | 极端长查询未截断 |
| L-7 | `embedding/DashScopeEmbeddingModel.java` | 边界 | 空文本列表调用 embedding API |
| L-8 | `embedding/DashScopeEmbeddingProperties.java` | 配置 | 无默认值校验 |
| L-9 | `retrieval/MmrDocumentPostProcessor.java` | 性能 | O(n²) 贪心算法无分批处理 |
| L-10 | `etl/EtlStatusManager.java` | 代码质量 | truncate() 可能拆分 surrogate pair |
| L-11 | `etl/EtlRouteStrategyFactory.java` | 边界 | 无策略匹配时抛 IllegalStateException |
| L-12 | `evaluation/metrics/` | 代码质量 | 部分 Scorer 硬编码 prompt |
| L-13 | `evaluation/runner/EvaluationRun.java` | 数据 | 缺少 createdAt/updatedAt 审计字段 |
| L-14 | `upload/ChunkUploadController.java` | 安全 | 缺少上传速率限制 |
| L-15 | `upload/PersonalUploadStrategy.java` | 边界 | 并发上传同名文件覆盖 |
| L-16 | `service/impl/DocumentValidator.java` | 边界 | MIME 类型检查大小写敏感 |
| L-17 | `event/` | 代码质量 | Event 类缺少 timestamp 字段 |

---

## 修复优先级建议

### P0 — 立即修复 (BLOCKER + 影响 H-RAG 效果)
1. **B-1** MinioFileStorageService OOM → 改流式返回
2. **B-2** 分片上传并发竞态 → 加 `__merging` 检查
3. **B-3** EncodingDetector 编译错误 → 删重复 import
4. **H-1** Parent Rescoring 失效 → 修正排序查找键

### P1 — 本迭代修复 (其他 HIGH)
5. **H-2~H-7** 边界防护 + 资源泄漏 + 内存防护
6. **H-8~H-9** 检索层降级处理
7. **H-10~H-13** 业务层性能 + 内存 + 配置一致性
8. **H-14** Embedding API 资源泄漏

### P2 — 下迭代 (MEDIUM)
按影响面排序，优先修复资源泄漏和并发安全问题。

### P3 — 有空再改 (LOW)
代码质量改进，不影响功能。
