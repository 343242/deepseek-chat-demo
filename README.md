# Chat Demo

基于 **Spring Boot 3.5 + Spring AI 1.1 + MyBatis-Plus 3.5** 的多厂商 AI 聊天助手后端。支持 **DeepSeek、智谱 AI (Zhipu)、MiniMax** 三家模型厂商，通过 Provider 抽象层实现统一路由。提供动态模型加载、SSE 流式响应、JDBC 对话记忆、**Tool Calling 工具调用**、RBAC 权限系统、滑块验证码、自研雪花 ID 与 UUIDv7，并通过 Advisor 链实现限流与内容安全过滤。

支持 **RAG（检索增强生成）**，通过 Apache Tika 多格式文档解析、Parent-Child 分块策略、PGvector 向量存储、阿里千问 text-embedding-v4 向量化，实现文档上传→解析→分块→向量化→检索增强的完整链路。检索管道支持**查询改写、混合检索（向量+BM25）+RRF融合、百炼Rerank精排、MMR多样性重排**四阶段优化。ETL 支持双线程池并发处理，小文档走**快速通道 BM25 即搜即用**（异步向量化补齐）。MinIO 对象存储通过 **BucketResolver** 实现个人/团队文档 bucket 隔离。

支持 **团队协作**，包括团队创建与解散、成员管理（邀请/移除/角色变更）、上传审批流、额度控制、文档权限隔离等完整功能。

使用 **Log4j 2** 替代 Logback 作为日志框架，基于 LMAX Disruptor 实现全异步日志，按级别（error/warn/info/debug）独立输出到不同目录。

## 技术栈

| 依赖 | 版本 | 用途 |
|------|------|------|
| Apache Tika | 随 Spring AI | 多格式文档解析（PDF/DOCX/PPTX/HTML 等） |
| exp4j | 0.4.8 | 安全数学表达式求值（替代 ScriptEngine） |
| Caffeine | 3.x | 本地缓存（SystemPrompt / ModelParams / 验证码） |
| LMAX Disruptor | 4.0.0 | 异步日志环形缓冲区（Log4j 2 内部引擎） |
| DashScope text-embedding-v4 | - | 阿里千问 Embedding 模型（1024 维） |
| Flyway | 随 Boot | 数据库版本化迁移（schema.sql 初始化） |
| Java | 21 | 运行时 |
| JJWT | 0.13.0 | JWT 双 Token（Access 15min + Refresh 24h） |
| Log4j 2 | 随 Boot | 日志框架（替代 Logback），按级别分目录 |
| MinIO | 9.0.0 | 对象存储（RAG 文件管理） |
| MyBatis-Plus | 3.5.16 | ORM 框架 |
| PGvector | 随 PostgreSQL | 向量数据库（pgvector 扩展） |
| PostgreSQL | 18 | 主数据库 |
| Redis | 8.2 | 缓存 / Token 存储 |
| sensitive-word | 0.29.5 | DFA 敏感词过滤（纯内存，14W+ QPS） |
| Spring AI | 1.1.6 | AI 模型集成 |
| Spring AI RAG | 1.1.6 | RetrievalAugmentationAdvisor + DocumentPostProcessor |
| Spring Boot | 3.5.14 | 应用框架 |
| Spring Data Redis | 随 Boot | Token 存储、权限缓存、IP 限流 |
| Spring Security | 随 Boot | 认证与授权 |
| Spring WebFlux | 随 Boot | WebClient（DashScope API 通信） |
| spring-ai-starter-model-deepseek | 1.1.6 | DeepSeek 模型接入 |
| spring-ai-starter-model-minimax | 1.1.6 | MiniMax 模型接入 |
| spring-ai-starter-model-zhipuai | 1.1.6 | 智谱 AI 模型接入 |
| UUIDv7 (RFC 9562) | 自实现 | 会话 ID 生成（时间有序 + 全局唯一） |

## 快速开始

### 1. 启动依赖服务

```bash
# 使用 docker compose 一键启动 PostgreSQL + Redis + MinIO
cp .env.example .env   # 编辑 POSTGRES_PASSWORD 等配置
docker compose up -d

# Flyway 自动执行数据库迁移：
#   V1__init_schema.sql                — 完整表结构（用户/权限/RBAC/聊天/RAG）
#   V2__vector_store_bm25.sql         — BM25 全文检索支持
#   V5__conversation_and_message.sql   — 会话 + 消息表（Session→Message 树形结构）
#   V6__backfill_conversation_and_message.sql — 历史对话数据回填
#   V9__team.sql                       — 团队协作表（team + team_member + team_upload_approval）
#   V10__rag_document_timestamptz.sql  — rag_document 时区类型 + DDL COMMENT
# 初始管理员：admin / admin123（生产环境请立即修改）
```

> **沙箱代码执行（可选）：** 如需 Tool Calling 代码执行功能，构建沙箱镜像：
> ```bash
> cd sandbox
> docker build -f Dockerfile.python -t sandbox-python:bookworm .
> docker build -f Dockerfile.node -t sandbox-node:bookworm .       # 需先拉取 node:22-bookworm
> docker build -f Dockerfile.java -t sandbox-java:bookworm .       # 需先拉取 eclipse-temurin:21-jre-bookworm
> ```

> PostgreSQL 18 变更了数据目录结构，docker-compose 已适配。如手动启动需注意 volume 挂载路径为 `/var/lib/postgresql`（而非旧版 `/var/lib/postgresql/data`）。

### 2. 配置环境变量

```bash
# 至少配置一个厂商的 API Key
export DEEPSEEK_API_KEY=sk-***
export ZHIPU_API_KEY=***         # 可选
export MINIMAX_API_KEY=***       # 可选

# RAG 配置（可选）
export DASHSCOPE_API_KEY=sk-***       # 阿里千问 Embedding + Rerank API Key

export JWT_SECRET=your-jwt-secret-at-least-32-characters-long!!

# 可选
export POSTGRES_PASSWORD=***
export REDIS_PASSWORD=***

# 雪花 ID（可选，默认 0）
export SNOWFLAKE_DATACENTER_ID=0
export SNOWFLAKE_WORKER_ID=0
```

### 3. 编译运行

```bash
mvn clean package -DskipTests
java -jar target/chat-demo-0.0.1-SNAPSHOT.jar
```

### 4. 验证

```bash
# 1. 获取验证码（dev 环境会返回 answer）
CAPTCHA=$(curl -s http://localhost:8080/api/auth/captcha)

# 2. 登录
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","captchaId":"<captchaId>","captchaCode":"<answer>"}' \
  -c cookies.txt

# 3. 查看可用模型（按厂商分组）
curl http://localhost:8080/api/models -b cookies.txt

# 4. 聊天 — 复合格式指定厂商
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -b cookies.txt \
  -d '{"model":"deepseek/deepseek-chat","message":"你好","conversationId":"test"}'

# 5. SSE 流式聊天
curl "http://localhost:8080/api/chat/stream?model=zhipu/glm-4.7&message=你好&conversationId=test" \
  -b cookies.txt

# 6. 上传文档（RAG）
curl -X POST http://localhost:8080/api/documents/upload \
  -b cookies.txt \
  -F "file=@document.pdf"

# 7. RAG 增强聊天
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -b cookies.txt \
  -d '{"model":"deepseek/deepseek-chat","message":"文档里讲了什么？","ragEnabled":true}'

# 8. 查看文档列表（仅当前用户的文档）
curl http://localhost:8080/api/documents -b cookies.txt

# 9. 删除文档（仅文档所有者可操作）
curl -X DELETE http://localhost:8080/api/documents/1 -b cookies.txt

# 10. 团队分片上传（大文件）
curl -X POST http://localhost:8080/api/teams/1/documents/multipart \
  -H "Content-Type: application/json" -b cookies.txt \
  -d '{"fileMd5":"d41d8cd98f00b204e9800998ecf8427e","fileName":"report.pdf","fileSize":52428800,"mimeType":"application/pdf","totalChunks":10}'

```

## 项目结构概览

```
src/main/java/com/demo/chat/
├── common/              # 公共模块（错误码、分页、雪花ID、UUIDv7、上传策略接口）
├── config/              # 基础配置（多厂商、Advisor、MyBatis-Plus、Redis）
├── security/            # 安全模块（JWT、验证码、Token缓存）
├── user/                # RBAC 用户模块（用户/角色/权限 CRUD）
├── conversation/        # 会话管理（独立于 chat，双写架构）
├── chat/                # 聊天核心（Provider 抽象、Advisor 链、Tool Calling）
├── rag/                 # RAG 检索增强（ETL Pipeline、文档解析、向量检索、个人上传策略、BucketResolver）
├── team/                # 团队协作模块（团队/成员/审批/文档权限/团队分片上传）
└── exception/           # 统一异常处理
```

> 完整的文件级目录结构见 [项目结构文档](docs/PROJECT-STRUCTURE.md)。



## 文档

| 文档 | 说明 |
|------|------|
| [架构设计](docs/ARCHITECTURE.md) | Provider 抽象、模型路由、Advisor 链、Tool Calling、设计原则 |
| [安全设计](docs/SECURITY.md) | 双 Token 认证、滑块验证码、RBAC 权限模型 |
| [会话与 ID 设计](docs/CONVERSATION-DESIGN.md) | 雪花 ID、UUIDv7、Conversation 双写架构 |
| [RAG 检索增强](docs/RAG-DESIGN.md) | ETL Pipeline、四阶段检索管道、多租户隔离 |
| [环境配置](docs/CONFIGURATION.md) | Profile 说明、环境变量、完整配置项 |
| [项目结构](docs/PROJECT-STRUCTURE.md) | 完整的源码目录树（文件级） |
| [API 接口文档](docs/API-DOCS.md) | 所有接口的完整说明 |
| [数据库设计文档](docs/DATABASE.md) | 表结构、索引、关系、Redis 使用 |
| [RBAC 用户模块设计](docs/RBAC-USER-MODULE-DESIGN.md) | 权限模型与用户管理 |
| [分片上传设计](docs/design/chunk-upload.md) | 分片上传架构、流程、安全措施 |
| [团队协作功能 PRD](docs/TEAM-FEATURE-PRD.md) | 团队创建/成员管理/审批流/额度控制/权限隔离 |
| [代码审查报告](docs/TEAM-CODE-REVIEW.md) | 团队模块代码审查（3B/7H/8M/4L） |

**外部参考：** [Spring AI 1.1.6](https://docs.spring.io/spring-ai/docs/1.1.6/api/) · [DeepSeek API](https://api-docs.deepseek.com/) · [智谱 AI API](https://docs.bigmodel.cn/cn/api/introduction) · [MiniMax API](https://platform.minimaxi.com/docs/api-reference/api-overview)

---

## License

MIT
