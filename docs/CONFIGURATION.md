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

## 团队配置

> 团队创建、成员管理及权限相关配置，定义在 `TeamProperties.java`。

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `app.team.approval-timeout-days` | 审批超时天数 | `7` |
| `app.team.default-creator-upload-limit-mb` | 新建团队时创建者的默认上传额度（MB） | `200` |
| `app.team.default-member-upload-limit-mb` | 新成员加入时的默认上传额度（MB） | `50` |
| `app.team.max-members-per-team` | 单个团队最大成员数 | `50` |
| `app.team.max-teams-per-user` | 单个用户最大加入团队数 | `10` |

```yaml
app:
  team:
    approval-timeout-days: 7
    default-creator-upload-limit-mb: 200
    default-member-upload-limit-mb: 50
    max-members-per-team: 50
    max-teams-per-user: 10
```

## Log4j 2 日志配置

> 日志配置文件：`src/main/resources/log4j2.xml`，替代 `application.yml` 中的 `logging.*` 配置。

### 核心特性

- **异步日志**：所有 Logger 使用 `AsyncLogger` / `AsyncRoot`，由 LMAX Disruptor 无锁队列驱动
- **日志目录**：默认 `./logs/`，可通过 JVM 参数 `-DLOG_PATH` 自定义
- **级别隔离**：`error` / `warn` / `info` / `debug` 四个独立子目录，各自滚动策略
- **控制台**：彩色输出（开发用）

### 滚动策略

| 级别 | 触发条件 | 单文件上限 | 保留天数 |
|------|---------|-----------|---------|
| `error` | 按天 + 大小触发 | 100 MB | 30 天 |
| `warn` | 按天 + 大小触发 | 200 MB | 30 天 |
| `info` | 按天 + 大小触发 | 500 MB | 30 天 |
| `debug` | 按天 + 大小触发 | 500 MB | 7 天 |

所有归档文件使用 gzip 压缩，过期文件自动清理。

### 环境变量调级

| JVM 参数 | 作用 | 默认值 |
|---------|------|--------|
| `-DLOG_ROOT_LEVEL` | 全局日志级别 | `INFO` |
| `-DLOG_APP_LEVEL` | 业务代码日志级别 | `INFO` |

示例：

```bash
java -jar chat-demo.jar -DLOG_ROOT_LEVEL=DEBUG -DLOG_APP_LEVEL=DEBUG
```

### 第三方框架降噪

以下框架默认日志级别设为 `WARN`，避免冗余输出：

- Spring Framework
- MyBatis
- MinIO Client
- Flyway

## 启动优化配置变更

> `ChatDemoApplication.java` 启动类的注解优化。

### 变更说明

| 注解 | 变更前 | 变更后 | 说明 |
|------|--------|--------|------|
| `@SpringBootApplication(scanBasePackages)` | 默认扫描 | `scanBasePackages = "com.demo.chat"` | 精确化包扫描，减少启动扫描开销 |
| `@ConfigurationPropertiesScan` | 未显式声明 | `@ConfigurationPropertiesScan("com.demo.chat")` | 覆盖所有模块的 `@ConfigurationProperties` 类 |
| `@MapperScan` | 单包或默认 | 精确列出 5 个 mapper 包 | 精确扫描各模块的 MyBatis Mapper 接口 |

### 示例

```java
@SpringBootApplication(scanBasePackages = "com.demo.chat")
@ConfigurationPropertiesScan("com.demo.chat")
@MapperScan({
    "com.demo.chat.user.mapper",
    "com.demo.chat.chat.mapper",
    "com.demo.chat.conversation.mapper",
    "com.demo.chat.rag.mapper",
    "com.demo.chat.team.mapper"
})
public class ChatDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatDemoApplication.class, args);
    }
}
```
