# 环境配置

> Profile 说明、环境变量、完整配置项参考。

## Profile 说明

| Profile | 用途 | 验证码 answer |
|---------|------|--------------|
| `dev` | 本地开发 | ✅ 返回 |
| `stable` | 测试/预发 | ❌ 不返回 |
| `prod` | 生产（叠加 stable） | ❌ 不返回 |

启动：`--spring.profiles.active=stable,prod`

## 关键配置项

```yaml
spring.ai:
  deepseek:
    base-url: https://api.deepseek.com
    api-key: ${DEEPSEEK_API_KEY}
    chat:
      model: deepseek-v4-flash
      temperature: 0.7
  zhipuai:
    base-url: ${ZHIPU_BASE_URL:https://open.bigmodel.cn/api/paas/v4}
    api-key: ${ZHIPU_API_KEY}
    chat:
      model: glm-4.7
      temperature: 0.7
  minimax:
    base-url: ${MINIMAX_BASE_URL:https://api.minimaxi.com/v1}
    api-key: ${MINIMAX_API_KEY}
    chat:
      model: MiniMax-M2.1
      temperature: 0.7

  # DashScope Embedding
  dashscope:
    embedding:
      base-url: ${DASHSCOPE_EMBEDDING_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      api-key: ${DASHSCOPE_API_KEY}
      model: text-embedding-v4
      dimensions: 1024

  # PGvector
  vectorstore:
    pgvector:
      index-type: HNSW
      distance-type: COSINE_DISTANCE
      dimensions: 1024
      initialize-schema: true
      table-name: vector_store

app:
  jwt:
    secret: ${JWT_SECRET}
    access-expiration: 900
    refresh-expiration: 86400
  snowflake:
    epoch: "2026-01-01T00:00:00+08:00"
    datacenter-id: 0
    worker-id: 0

  # RAG 文档管理
  minio:
    endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
    access-key: ${MINIO_ROOT_USER:minioadmin}
    secret-key: ${MINIO_ROOT_PASSWORD:minioadmin123}
    bucket: ${MINIO_BUCKET:rag-documents}

  # 文档上传配置
  document:
    chunk-strategy: parent-child
    parent-chunk-size: 2000
    child-chunk-size: 500
    max-file-size: 50MB
    allowed-mime-types: application/pdf,...

  # ETL 并发配置
  etl:
    executor:
      io:
        core-pool-size: 4
        max-pool-size: 8
        queue-capacity: 50
      cpu:
        core-pool-size: 2
        max-pool-size: 4
        queue-capacity: 20
      merge:
        core-pool-size: 2
        max-pool-size: 4
        queue-capacity: 20
        keep-alive-seconds: 120
    fast-track:
      enabled: true
      max-doc-count: 10
      max-total-size: 5MB

  # RAG 检索优化
  rag:
    query-rewrite-enabled: true
    hybrid-retrieval-enabled: true
    vector-top-k: 10
    bm25-top-k: 10
    rrf-k: 60
    rerank-enabled: true
    rerank-base-url: ${DASHSCOPE_RERANK_BASE_URL:https://dashscope.aliyuncs.com/compatible-api/v1}
    rerank-api-key: ${DASHSCOPE_API_KEY}
    rerank-model: qwen3-rerank
    rerank-top-n: 5
    mmr-enabled: true
    mmr-lambda: 0.7
    mmr-top-k: 5
    similarity-threshold: 0.5

model:
  router:
    default-provider: deepseek
```
