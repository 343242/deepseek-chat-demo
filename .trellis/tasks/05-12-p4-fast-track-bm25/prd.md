# P4: 快速通道 BM25

## 目标
小文档（≤10个且≤5MB）解析后直接写入 BM25 全文检索表，用户立即可检索；后续切分+向量化异步执行。

## 实现清单

### 1. BM25 快速写入
- 解析后获取原文 content
- 直接 INSERT 到 vector_store 表（id=UUID, content=原文, metadata={documentId, fastTrack:true}, embedding=NULL, content_tsv=to_tsvector('simple', content))
- 注意：embedding=NULL 是合法的，PGvector 不要求所有行都有向量
- 触发器自动填充 content_tsv（已有 V2 迁移的触发器）

### 2. 异步向量化
- 快速写入 BM25 后立即返回 status=COMPLETED
- 将 Transform + Load 任务提交到线程池异步执行
- 异步任务中：
  1. CPU池：分块（Transform）
  2. IO池：向量化写入（Load）
  3. 完成后删除 BM25 快速写入的原文行（按 metadata.fastTrack=true + documentId 过滤）
  4. 更新 chunkCount

### 3. 检索兼容
- HybridDocumentRetriever 的 BM25 搜索天然兼容（content_tsv 已有 GIN 索引）
- 向量检索时 embedding=NULL 的行不会命中（距离为空）
- 异步完成后精确分块覆盖原始行

### 4. 异步失败处理
- 异步 Transform/Load 失败不影响 BM25 可用性
- 失败时标记文档 status=VECTOR_FAILED（新增状态），BM25 仍可用
- 用户可重试向量化（可选：新增 API 端点）

## 线程安全
- BM25 INSERT 是独立事务，无竞态
- 异步任务使用 CompletableFuture，异常链完整传递
- 删除原文行时通过 FilterExpression 精确定位

## 验收
- [x] 小文档解析后 BM25 立即可检索
- [x] 异步向量化不阻塞响应
- [x] 向量化完成后替换原文行
- [x] 异步失败不影响 BM25
