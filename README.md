# Chat-Demo

基于 Spring Boot 3 + Spring AI 的智能对话系统，支持多模型路由、RAG 知识库检索、工具调用与用户隔离。

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Java 21 |
| 框架 | Spring Boot 3.5.14 |
| AI | Spring AI 1.1.6 |
| 数据库 | PostgreSQL + pgvector |
| ORM | MyBatis-Plus + Flyway |
| 缓存 | Redis + Caffeine |
| 对象存储 | MinIO |
| 认证 | Spring Security + JWT |
| LLM Provider | DeepSeek / 智谱(Zhipu) / MiniMax |

## 架构概览

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Client (SSE)                               │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────┐
│                        Spring Security                              │
│                    JWT (Cookie + Bearer) / RBAC                     │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────┐
│                          Chat Controller                            │
│                 流式 SSE · conversationId 隔离                      │
└──────┬───────────────────────┬───────────────────────┬──────────────┘
       │                       │                       │
┌──────▼──────┐  ┌─────────────▼────────────┐  ┌──────▼──────────────┐
│ ModelRouter │  │      ModeRouter          │  │   Advisor Chain     │
│ deepseek/   │  │ SIMPLE ─► 单轮           │  │                     │
│  deepseek-  │  │ MULTI_TURN ─► 多轮       │  │ RateLimit           │
│  chat       │  └────────────┬─────────────┘  │    ↓                │
│ zhipu/      │               │                │ ContentFilter       │
│  glm-4      │               │                │    ↓                │
└──────┬──────┘               │                │ [RAG Advisor] ◄─────┼── RAG Pipeline
       │                      │                │    ↓                │
┌──────▼──────────────────────▼──────┐         │ [ToolCall]          │
│         ProviderRegistry           │         │    ↓                │
│  ┌─────────┬─────────┬──────────┐  │         │ Memory              │
│  │DeepSeek │ Zhipu   │ MiniMax  │  │         └─────────────────────┘
│  └────┬────┴────┬────┴─────┬────┘  │
└───────┼─────────┼──────────┼───────┘
        │         │          │
        └────┬────┴──────────┘
             │
     ChatClientRegistry
             │
     ┌───────▼────────┐
     │  Tool Calling   │
     │ ┌─────────────┐ │
     │ │Calculator   │ │    ┌─────────────────────────────────────┐
     │ │(exp4j)      │ │    │          RAG Module                 │
     │ ├─────────────┤ │    │                                     │
     │ │DateTime     │ │    │  文件上传 → DocumentParserFactory    │
     │ ├─────────────┤ │    │    ├─ MarkdownDocumentParser        │
     │ │CodeExec     │ │    │    ├─ PdfDocumentParser             │
     │ │(Docker)     │ │    │    └─ TikaDocumentParser            │
     │ └─────────────┘ │    │           │                         │
     └─────────────────┘    │     ETL Pipeline                    │
                            │    ├─ FastTrackStrategy (小文档)     │
                            │    └─ StandardStrategy (标准)        │
                            │           │                         │
                            │     ChunkStrategy                   │
                            │    ├─ TokenChunkStrategy            │
                            │    ├─ StructureAwareChunkStrategy   │
                            │    └─ ParentChildChunkStrategy      │
                            │           │                         │
                            │     检索 Pipeline                   │
                            │    Rewrite → HybridRetriever        │
                            │    (pgvector + BM25, RRF 融合)      │
                            │    → BailianRerank → MMR →          │
                            │    ParentDocumentPostProcessor      │
                            └─────────────┬───────────────────────┘
                                          │
                            ┌─────────────▼───────────────────────┐
                            │          Storage Layer               │
                            │  PostgreSQL(pgvector) · Redis · MinIO│
                            └─────────────────────────────────────┘
```

## 模块说明

### Chat 模块 (`com.demo.chat.chat`)

核心对话引擎，处理所有 LLM 交互。

- **多 Provider 架构** — `ProviderRegistry` 管理多个 `ModelProvider`（DeepSeek / Zhipu / MiniMax），通过 `ChatClientRegistry` 统一调度
- **模型路由** — `ModelRouter` 支持复合格式 `deepseek/deepseek-chat` 和简单格式 `deepseek-chat`
- **对话模式** — `ModeRouter` 策略模式路由 `SIMPLE`（单轮）和 `MULTI_TURN`（多轮）
- **Advisor 链** — `RateLimit → ContentFilter → [RAG] → [ToolCall] → Memory`
- **工具调用** — `CalculatorTools`(exp4j 表达式)、`DateTimeTools`、`CodeExecutionTool`(Docker 沙箱)
- **用户隔离** — conversationId 格式 `u_{userId}_{rawId}`，所有数据按 userId 隔离
- **流式输出** — SSE 流式响应，支持部分响应保存和 Token 用量统计
- **令牌限流** — `TokenBucketLimiter` 纳秒精度，按 conversationId 独立限流
- **内容过滤** — `SensitiveWordFilter` + `ContentFilterService`

### RAG 模块 (`com.demo.chat.rag`)

检索增强生成，实现文档解析、分块、向量化、混合检索的完整 Pipeline。

**文档解析** — `DocumentParserFactory` 按 MIME 类型路由：
- `MarkdownDocumentParser` — 保留标题层级结构
- `PdfDocumentParser` — 按页切分
- `TikaDocumentParser` — 通用兜底，支持 PDF / DOCX / PPTX / HTML

**ETL Pipeline** — Extract → Transform → Load：
- `FastTrackStrategy` — 小文档 BM25 先行 + 异步向量化，快速可用
- `StandardStrategy` — 并发执行 Extract / Transform / Load

**分块策略** — `ChunkStrategyFactory` 策略模式：
- `TokenChunkStrategy` — 按 token 数机械切分
- `StructureAwareChunkStrategy` — 结构感知切分（Markdown/PDF/HTML/纯文本自适应）
- `ParentChildChunkStrategy` — 两层切分（父 2000 tokens + 子 500 tokens）+ `ParentDocumentPostProcessor` 回查

**检索 Pipeline**：
```
RewriteQueryTransformer
  → HybridDocumentRetriever (pgvector + BM25, RRF 融合)
    → BailianRerankPostProcessor
      → MmrDocumentPostProcessor
        → ParentDocumentPostProcessor
```

**RAG Advisor** — `RagAdvisorFactory` 按请求动态创建，携带 userId 隔离 filter。

### Security 模块 (`com.demo.chat.security`)

- JWT 认证（Cookie + Bearer 双模式）
- RBAC 权限控制：`chat:send` / `conversation:manage` / `model:config` / `document:manage`
- 图形验证码

### User 模块 (`com.demo.chat.user`)

- 用户注册 / 登录
- 角色 / 权限管理

## 快速开始

### 环境依赖

- JDK 21+
- PostgreSQL 15+ (需安装 pgvector 扩展)
- Redis 7+
- MinIO
- Docker（可选，用于代码执行沙箱）

### 配置

关键配置项在 `application.yml` 中：

```yaml
# 数据库
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/chatdemo
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}

# LLM Provider
spring.ai:
  deepseek:
    api-key: ${DEEPSEEK_API_KEY}
  zhipu:
    api-key: ${ZHIPU_API_KEY}
  minimax:
    api-key: ${MINIMAX_API_KEY}

# pgvector
spring.ai.vectorstore.pgvector:
  dimensions: 1536
  index-type: HNSW

# MinIO
minio:
  endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
  access-key: ${MINIO_ACCESS_KEY}
  secret-key: ${MINIO_SECRET_KEY}
  bucket: chat-demo

# Redis
spring.data.redis:
  host: ${REDIS_HOST:localhost}
  port: ${REDIS_PORT:6379}

# JWT
jwt:
  secret: ${JWT_SECRET}
  expiration: 86400000
```

### 环境变量

| 变量 | 说明 | 必需 |
|------|------|------|
| `DB_USERNAME` | PostgreSQL 用户名 | ✅ |
| `DB_PASSWORD` | PostgreSQL 密码 | ✅ |
| `DEEPSEEK_API_KEY` | DeepSeek API Key | 按需 |
| `ZHIPU_API_KEY` | 智谱 API Key | 按需 |
| `MINIMAX_API_KEY` | MiniMax API Key | 按需 |
| `MINIO_ACCESS_KEY` | MinIO Access Key | ✅ |
| `MINIO_SECRET_KEY` | MinIO Secret Key | ✅ |
| `JWT_SECRET` | JWT 签名密钥 | ✅ |
| `REDIS_HOST` | Redis 地址 | 默认 localhost |
| `REDIS_PORT` | Redis 端口 | 默认 6379 |

### 启动

```bash
# 克隆项目
git clone <repo-url> && cd chat-demo

# 启动依赖服务（Docker Compose）
docker compose up -d

# 运行数据库迁移（Flyway 自动执行）
# 编译启动
./mvnw spring-boot:run
```

## 开发指南

### 项目结构

```
src/main/java/com/demo/chat/
├── chat/              # 对话核心
│   ├── chat/          # Provider 路由、Advisor 链、工具调用
│   ├── rag/           # RAG Pipeline（独立模块，被 chat 通过 Advisor 调用）
│   └── ...
├── security/          # JWT 认证 + RBAC
├── user/              # 用户管理
├── common/            # 公共组件
└── exception/         # 全局异常处理
```

### 添加新 Provider

1. 实现 `ModelProvider` 接口
2. 注册到 `ProviderRegistry`
3. 在 `application.yml` 中配置对应 API Key

### 添加新文档解析器

1. 实现 `DocumentParser` 接口
2. 在 `DocumentParserFactory` 中注册 MIME 类型映射

### 添加新分块策略

1. 实现 `ChunkStrategy` 接口
2. 在 `ChunkStrategyFactory` 中注册

### 数据库迁移

使用 Flyway 管理，迁移脚本放在 `src/main/resources/db/migration/`，启动时自动执行。

### 用户隔离

所有业务数据（对话历史、文档、检索结果）均按 `userId` 隔离。新增功能时务必遵守此约定。

## 许可证

Private — 仅供学习参考。
