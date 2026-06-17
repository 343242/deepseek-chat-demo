# RAG 检索增强生成

> 文档上传→解析→分块→向量化→检索增强的完整链路设计。

## 整体流程

**整体流程：**

```
用户上传文档 (PDF/DOCX/MD/...)
        │
        ▼
DocumentController.upload()
        │
        ├── 1. 文件校验（MIME 白名单 + 大小限制 + 魔数 sniffing）
        ├── 2. MinIO 存储（FileStorageService）
        ├── 3. 创建 rag_document 记录（含 userId）
        └── 4. ETL 调度（EtlDispatchService → 策略路由）
                │
                ├─── 小文档？(≤10 个 且 ≤5MB) ──→ FastTrackStrategy
                │       │
                │       ├── IO 池并行 Extract
                │       ├── 同步写入 BM25 原文行 (embedding=NULL)
                │       ├── 立即返回 COMPLETED
                │       └── 异步 Transform + Load (CPU池→IO池)
                │           └── 完成后删除 BM25 行，替换为分块
                │           └── 失败标记 VECTOR_FAILED (BM25 仍可用)
                │
                └─── 大文档？──→ StandardStrategy
                        │
                        ├── IO 池并行 Extract
                        ├── CPU 池并行 Transform (分块)
                        └── IO 池并行 Load (写入 PGvector)

---

用户提问 + ragEnabled=true
        │
        ▼
ChatService → RetrievalAugmentationAdvisor
        │
        ▼  四阶段检索管道（各阶段可独立开关）
        │
        ├── Stage 1: 查询改写 — RewriteQueryTransformer
        │   └── LLM 将非正式查询转为结构化搜索词
        │
        ├── Stage 2: 混合检索 — HybridDocumentRetriever
        │   ├── pgvector HNSW 向量检索（语义相似度）
        │   ├── PostgreSQL tsvector 全文检索（BM25 词频匹配）
        │   └── RRF (Reciprocal Rank Fusion) 倒数排名融合
        │       公式：score(d) = Σ 1/(k + rank_i)
        │
        ├── Stage 3: 精排 — BailianRerankPostProcessor
        │   └── 调用阿里云百炼 qwen3-rerank 语义级重排
        │       API: POST /compatible-api/v1/reranks
        │
        ├── Stage 4: 多样性 — MmrDocumentPostProcessor
        │   └── MMR 公式：argmax [ λ·sim(q,d) - (1-λ)·max sim(d,d') ]
        │       消除语义冗余的检索结果
        │
        ├── Post: ParentDocumentPostProcessor
        │   └── 子切分 → 父文档替换 + parentId 去重
        │
        └── 父文档完整上下文 → 拼接到用户提问 → LLM 回答
```

**关键设计：**

- **并发 ETL**：IO/CPU 双线程池分离，Extract 和 Load 走 IO 池，Transform 走 CPU 池，每个文档状态独立事务
- **策略路由**：`EtlRouteStrategyFactory` 自动发现所有策略 Bean，按 `order` 排序，FastTrack 优先判定
- **快速通道 BM25**：小文档（≤10 个且 ≤5MB）原文直接写入 `vector_store`（embedding=NULL），BM25 即搜即用，异步完成向量化后替换
- **四阶段管道**：查询改写→混合检索→Rerank→MMR，各阶段通过 `app.rag.*` 配置独立开关
- **混合检索 + RRF**：向量检索捕捉语义相关性，BM25 捕捉精确关键词匹配，RRF 融合两者优势
- **BM25 全文检索**：通过 Flyway V2 迁移给 `vector_store` 表添加 `content_tsv` 列 + 触发器 + GIN 索引
- **百炼 Rerank**：qwen3-rerank 模型进行语义级精排，比向量相似度更精准
- **MMR 多样性**：λ=0.7 平衡相关性与多样性，消除重复内容
- **策略模式**：`DocumentParser` 接口 + `ChunkStrategy` 接口，各自可独立扩展
- **Parent-Child 策略**：子切分保证检索精度（500 tokens），父文档保证 LLM 上下文完整性（2000 tokens）
- **SRP 重构**：`DocumentApplicationService` 接口抽离业务逻辑，Controller 仅做 HTTP 层
- **资源级授权**：`RagDocument` 含 `userId`，`findAndVerifyOwner()` 统一校验，防枚举攻击
- **MIME 安全校验**：白名单 + 文件大小限制 + 文件头魔数 sniffing，防止伪造 Content-Type
- **向量清理**：删除文档时通过 `documentId` metadata 精准清理 PGvector 中的所有关联 chunk
- **Embedding 防护**：空文本返回缓存零向量，API 调用加 30s Duration timeout
- **ETL 解耦**：`Extractor`/`Transformer`/`Loader` 独立接口，Pipeline 只做编排，零业务逻辑
- **DashScope Embedding**：通过 WebClient 调用阿里千问 OpenAI 兼容 API，实现 `EmbeddingModel` 接口

## 分片上传

支持大文件（≤50MB）分片上传，提供秒传、断点续传、异步合并能力。详见 [分片上传设计文档](docs/design/chunk-upload.md)。

```
客户端                           服务端
  │ POST /multipart (init) ───→ 创建 session / 秒传命中返回 200
  │ PUT /chunks/{index} ×N  ──→ Lua 原子写入 + 幂等检查
  │ POST /complete          ──→ composeObject 合并 + MD5 校验 + ETL
  │ GET  /multipart/{id}    ──→ 断点续传：返回已上传分片列表
  └ DELETE /multipart/{id}  ──→ 取消上传，清理资源
```

**关键设计：**

- **秒传**：init 时检查 `fileMd5`，相同文件直接复用已有文档
- **断点续传**：status 接口返回已上传分片列表，客户端跳过已传分片
- **异步合并**：最后一个分片或 complete 触发 composeObject 合并，失败可重试
- **安全**：multipart 大小限制 + uploadId UUID 校验 + init 速率限制 + 服务端 MD5 校验
- **Redis 原子操作**：Lua 脚本保证分片记录 + 完成判定 + 合并锁原子性
- **孤儿清理**：每 6h 扫描，48h 阈值，removeObjects 批量删除

## 多租户隔离

所有 RAG 操作均按 `userId` 严格隔离，防止跨用户数据泄露：

| 环节 | 隔离机制 |
|------|----------|
| **文档上传** | `rag_document.user_id` 绑定当前用户，`EtlCandidate` 全链路携带 userId |
| **向量检索** | `FilterExpressionBuilder.eq("userId", userIdStr)` 过滤，只检索当前用户的 chunk |
| **BM25 检索** | SQL `AND metadata->>'userId' = ?` 过滤，只匹配当前用户的 chunk |
| **RAG Advisor** | `RagAdvisorFactory.create(userId)` 每次请求动态创建，替代全局单例 Bean |
| **chunk metadata** | 所有 chunk 必须包含 `userId`（String 类型）和 `documentId`（String 类型） |
| **文档管理** | `findAndVerifyOwner()` 统一 owner 校验，非文档所有者无法查看/删除 |

> **上线注意：** 旧 `vector_store` 数据若无 `userId` metadata，需执行回填 SQL（关联 `rag_document.user_id`）后才能被隔离检索。

**分块策略对比：**

| 策略 | 切分方式 | 适用场景 | 配置值 |
|------|---------|---------|--------|
| token | Token 数机械切分 | 格式不固定的文档 | `token` |
| structure-aware | 结构感知切分：自动检测文档结构类型（Markdown 标题/PDF 页码/HTML 标签），按结构边界切分，无结构时降级到段落逻辑 | Markdown/PDF/HTML/混合格式 | `paragraph` |
| parent-child | 双层切分（父 2000t / 子 500t） | 精准检索 + 完整上下文 | `parent-child` |

**检索参数配置（`app.rag.*`）：**

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `query-rewrite-enabled` | `true` | 查询改写开关 |
| `hybrid-retrieval-enabled` | `true` | 混合检索开关（关闭则纯向量） |
| `vector-top-k` | `10` | 向量检索 topK |
| `bm25-top-k` | `10` | BM25 全文检索 topK |
| `rrf-k` | `60` | RRF 常数（越小对高排名越敏感） |
| `rerank-enabled` | `true` | Rerank 开关 |
| `rerank-model` | `qwen3-rerank` | Rerank 模型 |
| `rerank-top-n` | `5` | Rerank 返回数量 |
| `mmr-enabled` | `true` | MMR 开关 |
| `mmr-lambda` | `0.7` | 平衡参数（0=最大多样性，1=最大相关性） |
| `mmr-top-k` | `5` | MMR 返回数量 |
| `similarity-threshold` | `0.5` | 向量相似度阈值 |
