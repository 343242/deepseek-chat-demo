# RAG 模块优化复盘

## 执行结果

`9cca28f` — 13 文件，+253/-82 行

| 优先级 | 优化项 | 状态 | 关键改动 |
|--------|--------|------|---------|
| P0 | 单文件上传异步化 | ✅ | `dispatchAsync()` + IO 线程池执行 |
| P0 | 父文档回查 N+1 → 批量 | ✅ | JdbcTemplate IN 查询替代逐个向量检索 |
| P0 | 去重 getStatus | ✅ | 删除重复接口 |
| P1 | Advisor 缓存 | ✅ | ConcurrentHashMap<userId, Advisor> |
| P1 | Embedding 超时可配置 | ✅ | Properties 注入，默认 10s |
| P1 | ObjectMapper 统一注入 | ✅ | 3 处 new ObjectMapper() → Spring Bean |
| P1 | uploadBatch 去立即查询 | ✅ | 统一返回 PROCESSING |
| P2 | ETL 失败重试 | ✅ | POST /{id}/retry + deleteVectors |
| P2 | Embedding 限流 backoff | ✅ | 3 次重试，指数退避 |
| P2 | 遗留代码清理 | ✅ | EtlPipelineServiceImpl 错误信息更新 |

## 核心教训

1. **上传接口应始终异步**：同步 ETL 意味着用户等 10-30 秒才能拿到响应，体验极差。`uploadBatch()` 已异步，`upload()` 漏掉了——说明功能对称性审查很重要。

2. **向量库不是万能查询工具**：`ParentDocumentPostProcessor` 用 `VectorStore.similaritySearch()` 按元数据做精确查找，本质是拿语义搜索做 KV 查询，完全浪费了向量计算。底层是 PG 时直接 SQL 查更合理。

3. **ObjectMapper 应该是单例**：Jackson ObjectMapper 线程安全、创建开销大。Spring Boot 自动配置了一个，应该直接注入。手动 new 的都应替换。

4. **外部 API 调用必须加重试**：DashScope Embedding API 可能因限流返回 429，以前直接失败导致整个 ETL 失败。加上重试 + 指数退避是必须的。

5. **缓存无状态对象**：`RagAdvisorFactory.create()` 每次创建新实例，但组件链都是无状态的（只有 userId 不同），按 userId 缓存避免重复构建。
