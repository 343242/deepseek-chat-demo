# Smart-RAG

基于 **Spring Boot 3.5 + Spring AI 1.1 + MyBatis-Plus 3.5** 的生产级 Agentic RAG 系统。核心能力包括：**统一 LLM SPI**（`infrastructure/llm/` 中 `LlmClientRegistry` + `CapabilityClient` 抽象 Chat / Embedding / Rerank 三类能力，覆盖阿里百炼 / DeepSeek / 智谱 / MiniMax，Retry + Circuit Breaker 内建，与 Spring AI `ChatClient.Builder` 自动配置解耦）、**Agentic RAG**（意图识别驱动 Agent 主循环 + RAG 工具族 + Guardrail + Trace）、**六阶段 RAG Pipeline**（查询改写 → 混合检索 → MMR 去冗余 → Rerank 精排 → 父文档回查 → LLM 生成）、**Tool Calling 代码沙箱执行**、RBAC 权限、团队协作与文档增量更新。

## 功能概览

### 多厂商 AI 聊天

- **统一 LLM SPI**：所有厂商经由 `infrastructure/llm/` 中的 `LlmClientRegistry` + `CapabilityClient`（Chat / Embedding / Rerank 三类能力）+ `ChatModelAdapter`（桥接 Spring AI `ChatModel`）+ `RewriteClientResolver`（chat / rag / agent 上层入口）装配，与 Spring AI `ChatClient.Builder` 自动配置完全解耦
- **多厂商 Provider**：阿里百炼 (DashScope，承载 Qwen / Embedding / Rerank)、DeepSeek、智谱 AI (Zhipu)、MiniMax —— 厂商差异通过 `ProviderClientFactory` 策略族封装，新增厂商零改主链路
- **registry 候选 ID 指定模型**：`deepseek-v4-flash`、`qwen-plus`、`qwen3-max`，**不接受 `provider/modelId` 复合格式**（service 入口 fail-fast 校验）
- **韧性内建**：Retry + Circuit Breaker（`app.llm.resilience.*`），降级链通过 `LlmClientRegistry.getChain(capability)` 暴露
- **Advisor 链**：限流（令牌桶）→ 内容安全过滤 → 会话上下文注入 → RAG 增强 → Tool Calling 循环 → 对话记忆写入
- **SSE 流式响应**：原生 Server-Sent Events，支持 Fallback 重试链
- **JDBC + Redis 对话记忆**：Spring AI Chat Memory 双层持久化
- **Tool Calling**：沙箱隔离执行 Python / Node.js / Java 代码，安全数学表达式求值，日期时间工具
- **聊天模式策略**：Simple（单轮） / MultiTurn（多轮上下文），`ModeRouter` 自动路由
- **CAG 上下文注入**：基于用户画像 / 会话策略 / 策略约束的 Prompt 动态组装

### Agentic RAG（智能体检索）

- **意图识别**：`app.agent.intent-model`（registry 候选 ID）路由请求，命中 RAG 意图进入 Agent 主循环
- **Agent 主循环**：`max-tool-iterations` / `max-consecutive-same-tool` 双限位，工具调用循环可观测（`AgentTrace` / `ToolCallRecord`）
- **工具族**（`agent/tool/`）：`VectorSearchTool` / `Bm25SearchTool` / `HybridSearchTool` / `RerankTool` / `QueryRewriteTool` / `ParentDocLookupTool` / `DocDetailTool` / `KnowledgeBaseInfoTool` / `AgentEventLookupTool`，统一通过 `AgentToolCallbackFactory` 注入
- **Guardrail 与降级**：`agent/guardrail/` 守门，`agent.intent-timeout-ms` 与 `degrade-on-failure` 控制 backoff 到普通 RAG
- **Workspace**：`ToolWorkspace` + `ToolWorkspaceFactory` 隔离每次会话的检索上下文（`RetrievedDocument`）

### RAG 检索增强生成

支持完整的 RAG Pipeline：文档上传（含 GBK/GB2312/GB18030 编码自动检测转码）→ Apache Tika 解析 → Parent-Child 分块 → DashScope Embedding 向量化 → PGvector 存储。

**六阶段检索 Pipeline（H-RAG 优化后）：**

```
用户查询
  ↓ QueryNormalize（全角→半角、NFC、空白压缩）
  ↓ RewriteQueryTransformer（LLM 查询改写，短查询原样透传守卫）
  ↓ HybridDocumentRetriever（pgvector 向量检索 topK=30 + BM25 全文检索 topK=30, RRF 加权融合）
  ↓ MmrDocumentPostProcessor（MMR 多样性去冗余 topK=10, pgvector cosine 数据库层计算）
  ↓ BailianRerankPostProcessor（百炼 Rerank 语义精排 topN=5, 三次重试+指数退避）
  ↓ ParentDocumentPostProcessor（子块→父文档回查替换, max(childScore) 降序排列）
  ↓ 注入 LLM prompt → 流式生成
```

关键设计：
- **H-RAG 论文驱动优化**：Query Rewrite 守卫规则（短查询原样透传）、自定义 Rewrite 模型与 temperature、Parent-level Rescoring（子块 max-score 聚合排序父文档）
- **混合检索 + RRF 加权融合**：向量语义检索与 BM25（pg_jieba 中文分词）互补，向量检索利用 cosine 相似度分数加权
- **先去冗余再精排**：MMR 在 Rerank 之前执行，避免 Rerank 浪费算力在语义重复文档上
- **HNSW 参数调优**：m=32, ef_construction=128, ef_search=64, iterative_scan=relaxed_order
- **ETL 双线程池**：IO 池（Extract/Load）+ CPU 池（Transform），多文档并行处理
- **快速通道**：小文档走 BM25 即搜即用（异步向量化补齐）
- **文档增量更新**：replaceDocumentId 方案，版本关联 + 旧向量即时清理 + 启动补偿
- **MinIO BucketResolver**：个人/团队文档 bucket 隔离
- **编码自动检测**：文本/Markdown 上传自动识别 GBK/GB2312/GB18030 编码并转码为 UTF-8

### 评估系统

独立的 RAG 评估框架（`@Profile("evaluation")`，dev 环境零侵入）：
- **检索侧**：召回率 (Recall@K)、准确率 (Precision@K)、MRR、NDCG
- **生成侧**：忠实度 (Faithfulness)、答案相关性、上下文相关性、上下文召回率
- **Judge 模型**：独立于 Provider 路由，通过 `application-evaluation.yml` 直连厂商 API

### 团队协作

团队创建与解散、成员管理（邀请/移除/角色变更）、上传审批流、额度控制、文档权限隔离、团队分片上传。

### 安全

- **JWT 双 Token 认证**：Access Token（15min）+ Refresh Token（24h），JJWT 0.13.0
- **RBAC 权限模型**：8 个细粒度权限，Spring Security `@PreAuthorize` 注解保护
- **滑块验证码**：Caffeine 缓存存储
- **敏感词过滤**：DFA 算法（sensitive-word）

## 技术栈

| 依赖 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 运行时 |
| Spring Boot | 3.5.14 | 应用框架 |
| Spring AI | 1.1.6 | AI 模型集成（百炼 / DeepSeek / 智谱 / MiniMax，统一 LLM SPI 适配） |
| MyBatis-Plus | 3.5.16 | ORM 框架 |
| PostgreSQL | 18 | 主数据库 |
| PGvector | 0.8.2 | 向量数据库（HNSW 索引） |
| pg_jieba | — | 中文分词（BM25 全文检索） |
| Redis | 8.2 | 缓存 / Token 存储 / 权限缓存 / 会话记忆 |
| MinIO | 9.0.0 | 对象存储（文档管理） |
| Flyway | 随 Boot | 数据库版本化迁移（14 个版本） |
| Spring Security | 随 Boot | JWT 双 Token 认证 + RBAC 授权 |
| JJWT | 0.13.0 | JWT 生成与解析 |
| Apache Tika | 随 Spring AI | 多格式文档解析（PDF/DOCX/PPTX/HTML 等） |
| juniversalchardet | 2.5.0 | 文本编码自动检测（GBK/GB2312/GB18030） |
| DashScope text-embedding-v4 | — | 阿里千问 Embedding 模型（1024 维，经百炼 Provider） |
| DashScope qwen3-rerank | — | 阿里千问 Rerank 模型（经百炼 Provider，BailianRerankPostProcessor 调用） |
| Caffeine | 3.x | 本地缓存（SystemPrompt / ModelParams / 验证码） |
| sensitive-word | 0.29.5 | DFA 敏感词过滤 |
| Log4j 2 + Disruptor | 4.0.0 | 全异步日志，按级别分目录 |
| exp4j | 0.4.8 | 安全数学表达式求值 |
| UUIDv7 (RFC 9562) | 自实现 | 会话 ID 生成（时间有序 + 全局唯一） |

## 快速开始

### 1. 启动依赖服务

```bash
# 使用 docker compose 一键启动 PostgreSQL + Redis + MinIO
cp .env.example .env   # 编辑 POSTGRES_PASSWORD 等配置
docker compose up -d

# Flyway 自动执行数据库迁移（共 14 个版本）：
#   V1  初始表结构（用户/权限/RBAC/聊天）
#   V2  BM25 全文检索支持（tsvector + 触发器）
#   V3  初始角色与权限种子数据
#   V4  pg_jieba 中文分词配置
#   V5  会话 + 消息表
#   V6  历史对话数据回填
#   V7  Spring AI Chat Memory 表
#   V8  rag_document file_md5 字段
#   V9  团队协作表
#   V10 rag_document 时区类型 + DDL COMMENT
#   V11 RAG 评估表
#   V12 评估状态 CHECK 约束 + 权限
#   V13 HNSW 参数调优 + iterative scan
#   V14 文档增量更新（version/superseded_by/document_group_id）
#   V15 agent_session_event 表
#   V16 llm_config 表（BYOK）
#   V17 MCP Admin 配置表（mcp_server_config/mcp_tool_config/mcp_security_config）+ admin_audit_log
# 初始管理员：admin / admin123（生产环境请立即修改）
```

> **沙箱代码执行（可选）：** 如需 Tool Calling 代码执行功能，构建沙箱镜像：
> ```bash
> cd sandbox
> docker build -f Dockerfile.python -t sandbox-python:bookworm .
> docker build -f Dockerfile.node -t sandbox-node:bookworm .
> docker build -f Dockerfile.java -t sandbox-java:bookworm .
> ```

### 2. 配置环境变量

```bash
# 至少配置一个厂商的 API Key
export DEEPSEEK_API_KEY=sk-***
export ZHIPU_API_KEY=***         # 可选
export MINIMAX_API_KEY=***       # 可选

# RAG / 阿里百炼（可选但强烈推荐：Qwen 系 Chat + Embedding + Rerank 都走此 key）
export DASHSCOPE_API_KEY=sk-***       # 百炼 Provider（qwen-plus / qwen3-max / text-embedding-v4 / qwen3-rerank）

# 安全
export JWT_SECRET=your-jwt-secret-at-least-32-characters-long!!

# 可选
export POSTGRES_PASSWORD=***
export REDIS_PASSWORD=***
export SNOWFLAKE_DATACENTER_ID=0
export SNOWFLAKE_WORKER_ID=0
```

### 3. 编译运行

```bash
mvn clean package -DskipTests
java -jar target/smart-rag-0.0.1-SNAPSHOT.jar
```

### 4. 验证

```bash
# 1. 获取验证码（dev 环境会返回 answer）
curl -s http://localhost:8080/api/auth/captcha

# 2. 登录
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123","captchaId":"<captchaId>","captchaCode":"<answer>"}' \
  -c cookies.txt

# 3. 查看可用模型（按厂商分组）
curl http://localhost:8080/api/models -b cookies.txt

# 4. 聊天 — model 字段传 registry 候选 ID（不带 provider/ 前缀）
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" -b cookies.txt \
  -d '{"model":"deepseek-v4-flash","message":"你好","conversationId":"test"}'

# 5. SSE 流式聊天
curl "http://localhost:8080/api/chat/stream?model=qwen-plus&message=你好&conversationId=test" \
  -b cookies.txt

# 6. 上传文档（RAG，支持 GBK/GB2312/GB18030 自动转码）
curl -X POST http://localhost:8080/api/documents/upload \
  -b cookies.txt -F "file=@document.pdf"

# 7. RAG 增强聊天
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" -b cookies.txt \
  -d '{"model":"deepseek-v4-flash","message":"文档里讲了什么？","ragEnabled":true}'

# 8. 查看文档列表（仅当前用户的文档）
curl http://localhost:8080/api/documents -b cookies.txt

# 9. 删除文档（仅文档所有者可操作）
curl -X DELETE http://localhost:8080/api/documents/1 -b cookies.txt

# 10. 团队分片上传（大文件）
curl -X POST http://localhost:8080/api/teams/1/documents/multipart \
  -H "Content-Type: application/json" -b cookies.txt \
  -d '{"fileMd5":"d41d8cd98f00b204e9800998ecf8427e","fileName":"report.pdf","fileSize":52428800,"mimeType":"application/pdf","totalChunks":10}'
```

## 项目结构

```
src/main/java/com/smart/rag/
├── SmartRagApplication.java
├── common/              # 公共模块（错误码、分页、雪花ID、UUIDv7、工具类）
├── config/              # 基础配置（MyBatis-Plus、Redis、CORS、Caffeine、Advisor 装配）
├── user/                # RBAC 用户模块（用户/角色/权限 CRUD、BCrypt）
├── conversation/        # 会话管理（独立于 chat，双写架构）
├── chat/                # 聊天业务编排（HTTP 入口）
│   ├── context/         #   CAG 上下文注入（用户画像/会话策略/策略约束）
│   ├── controller/      #   REST 接口（/api/chat、/api/models、/api/usage）
│   ├── dto/             #   请求/响应 record
│   ├── entity/          #   实体（SystemPrompt、ModelParams、TokenUsage）
│   ├── mapper/          #   MyBatis-Plus Mapper（XML SQL）
│   ├── mode/            #   聊天模式策略（Simple / MultiTurn）
│   ├── service/         #   业务编排（ChatServiceImpl 在此 fail-fast 校验 candidate ID）
│   └── tool/            #   通用 Tool Calling（Calculator / DateTime / CodeExecution + 沙箱）
├── agent/               # Agentic RAG 模块（意图驱动）
│   ├── advisor/         #   AgentSystemPromptAdvisor
│   ├── intent/          #   意图识别（intent-model 路由）
│   ├── guardrail/       #   Agent 守门（输入/输出兜底）
│   ├── mode/            #   AgentModeStrategy
│   ├── workspace/       #   ToolWorkspace / RetrievedDocument（单轮隔离）
│   ├── service/         #   HybridSearchService 等编排服务
│   ├── tool/            #   RAG 工具族（Vector/BM25/Hybrid/Rerank/Rewrite/DocDetail/...）
│   │   ├── callback/    #     AgentToolCallbackFactory（统一回调装配）
│   │   └── dto/         #     工具入参 record
│   ├── trace/           #   AgentTrace / ToolCallRecord（可观测）
│   ├── event/           #   事件 + payload（Agent 流程埋点）
│   └── config/          #   AgentRagProperties（app.agent.*）
├── rag/                 # RAG 模块（Pipeline 与 ETL）
│   ├── config/          #   RagRetrievalProperties record、RagAdvisorFactory
│   ├── retrieval/       #   HybridDocumentRetriever、Rerank、MMR、QueryNormalizer
│   ├── chunk/           #   Parent-Child 策略、ParentDocumentPostProcessor（含 Rescoring）
│   ├── embedding/       #   向量化（DashScopeEmbeddingModel、批量分片、场景识别）
│   ├── etl/             #   ETL Pipeline（双线程池、Standard/FastTrack 策略路由）
│   ├── mapper/          #   数据访问（VectorStoreMapper: BM25/ParentChild/Cosine距离）
│   ├── evaluation/      #   评估系统（@Profile("evaluation"), Judge/LlmScorer/指标）
│   ├── event/           #   文档事件（增量更新、版本替换）
│   ├── parser/          #   文档解析（Apache Tika + 编码自动检测）
│   ├── service/         #   RAG 业务服务（文档管理、增量更新）
│   ├── upload/          #   上传策略（个人上传）
│   └── entity/          #   RAG 实体
├── team/                # 团队协作（团队/成员/审批/额度/权限隔离/团队上传）
├── evaluation/          # RAG 评估框架（@Profile("evaluation")，dev 零侵入）
└── infrastructure/      # 基础设施层（跨业务复用）
    ├── llm/             #   ⭐ 统一 LLM SPI（核心）
    │   ├── CapabilityClient.java       # 能力客户端根接口（candidateId/providerId/modelName）
    │   ├── ChatCapable.java            #   Chat 能力契约
    │   ├── EmbeddingCapable.java       #   Embedding 能力契约
    │   ├── RerankResult.java           #   Rerank 结果
    │   ├── ToolCallingCapable.java     #   工具调用能力
    │   ├── ChatRequest.java            #   统一请求载体
    │   ├── registry/                   #   LlmClientRegistry（无锁读写分离）+ LlmClientFactory + RegistrySnapshot
    │   ├── adapter/                    #   ChatModelAdapter（桥接 Spring AI ChatModel）+ RewriteClientResolver（上层入口）
    │   ├── client/                     #   AbstractChatClient + bailian/ + generic/（厂商实现）
    │   ├── provider/                   #   ModelCandidate + ProviderClientFactory + generic/
    │   ├── strategy/                   #   EmbeddingCapabilityStrategy / RerankCapabilityStrategy + provider/（Bailian 工厂）
    │   ├── resilience/                 #   Retry + CircuitBreaker（app.llm.resilience.*）
    │   ├── metrics/                    #   LLM 调用埋点
    │   └── config/                     #   ResilienceConfig 等
    ├── advisor/         #   Advisor 链公共组件（RateLimit / ContentFilter / ConversationContext）
    ├── content/         #   内容安全（敏感词 DFA）
    ├── memory/          #   Redis 会话记忆（Spring AI ChatMemory 适配）
    ├── fallback/        #   Fallback 自动降级（重试链 + 资格判定）
    ├── concurrent/      #   ScopedTasks / TaskScope / ScopePolicy（并发与超时）
    ├── messaging/       #   RocketMQ 适配
    ├── exception/       #   RemoteException / GlobalExceptionHandler / ErrorCode 枚举
    ├── request|response|stream|web/  # HTTP 载体与 Web 层公共组件
    └── model/           #   跨业务领域模型（与 entity 区分）
```

> 完整的文件级目录结构见 [项目结构文档](docs/PROJECT-STRUCTURE.md)。
>
> LLM SPI 详细契约（注入规则、模型 ID 格式、错误矩阵）见 [`.trellis/spec/backend/llm-spi.md`](.trellis/spec/backend/llm-spi.md)。

## 生产部署（单机全栈容器化）

面向单台 VPS 的生产部署方案：中间件 + 应用 + 反向代理 + 自动 HTTPS 全部跑在 `docker compose` 里。

### 资源需求

| 规格 | 说明 |
|------|------|
| **最低** | 4 vCPU / 8 GB RAM / 40 GB SSD |
| **推荐** | 4 vCPU / 16 GB RAM / 80 GB SSD（文档量大或并发高时） |
| **操作系统** | 任意 Linux 发行版（Ubuntu 22.04 / Debian 12 验证过），装好 Docker Engine + Compose v2 即可，无需 JDK |

内存分配（8GB VPS 实测）：PostgreSQL ~700MB + Redis ~350MB + MinIO ~450MB + RocketMQ(broker+proxy+dashboard) ~1.8GB + 应用 JVM ~2GB + Nginx ~50MB + OS ~1GB ≈ 6.5GB，留 1.5GB buffer。

> **RocketMQ 不可省略**：3 个 `SmartLifecycle` Consumer（`ChatMessageSaveConsumer` / `UsageRecordConsumer` / `EtlDocumentConsumer`）在应用启动时同步建链，broker 不可达会让 ApplicationContext 启动失败。

### 部署步骤

```bash
# 1. 克隆代码到服务器
git clone <repo-url> smart-rag && cd smart-rag

# 2. 准备环境变量（必须把所有 ⚠️ 必填项改掉）
cp .env.example .env
vim .env   # 重点：SERVER_NAME / 各种密码 / 4 个 LLM API key / JWT_SECRET

# 3. （首次）申请 Let's Encrypt 证书 —— 需要 80 端口公网可达且 DNS A 记录已生效
./scripts/init-ssl.sh

# 4. 启动完整栈
docker compose -f docker-compose.prod.yml up -d

# 5. 等待应用就绪（Flyway 迁移 + RocketMQ subscribe 大约 60-90s）
docker compose -f docker-compose.prod.yml logs -f app
# 看到 "Started SmartRagApplication" 即就绪

# 6. 验证
curl https://${SERVER_NAME}/actuator/health
# 期望: {"status":"UP"}
```

### 日常运维

```bash
# 更新代码并重新构建应用（中间件不动，热更应用）
git pull
docker compose -f docker-compose.prod.yml up -d --build app

# 查看实时日志
docker compose -f docker-compose.prod.yml logs -f app

# 进入应用容器排查
docker compose -f docker-compose.prod.yml exec app sh

# 优雅重启（30s drain in-flight 请求）
docker compose -f docker-compose.prod.yml restart app

# 完全停机（保留数据卷）
docker compose -f docker-compose.prod.yml down
```

### 网络拓扑

```
Internet ──► nginx (80/443) ──► app (8080)
                                  │
                                  ├── postgres (5432, 内部)
                                  ├── redis    (6379, 内部)
                                  ├── minio    (9000, 内部)
                                  └── rmqbroker(8081, 内部)
```

**只有 nginx 暴露 80/443 到公网**，所有中间件仅在 `smart-rag-net` 内部网络，无法从公网直连。

### 故障排查

| 现象 | 排查方向 |
|------|---------|
| `docker compose up` 后 app 容器反复重启 | `docker compose logs app` 看是否 env 缺失（常见：`JWT_SECRET` / 4 个 LLM key 未填） |
| 应用启动卡在 RocketMQ subscribe | 确认 `rmqbroker` 健康检查通过：`docker compose ps rmqbroker` |
| HTTPS 访问报 502 | app 未就绪，检查 `docker compose logs app` 是否还在 Flyway 迁移 |
| 证书续期失败 | `docker compose logs certbot`；手动续：`docker compose run --rm certbot certonly --webroot --webroot-path /var/www/certbot -d ${SERVER_NAME}` |
| 文档上传 413 | nginx `client_max_body_size 60m` 已设，检查是否被外层 CDN/ALB 截断 |

### 备份与恢复

数据卷：`pgdata` / `redisdata` / `miniodata` / `rmqdata`。生产建议每日定时备份 PG：

```bash
# 备份（每日 cron）
docker compose -f docker-compose.prod.yml exec -T postgres \
    pg_dump -U ${POSTGRES_USER} ${POSTGRES_DB} | gzip > backup-$(date +%F).sql.gz

# 恢复
gunzip -c backup-2026-07-19.sql.gz | docker compose -f docker-compose.prod.yml exec -T postgres psql -U ${POSTGRES_USER} ${POSTGRES_DB}
```

## 文档

### 设计文档

| 文档 | 说明 |
|------|------|
| [架构设计](docs/ARCHITECTURE.md) | Provider 抽象、模型路由、Advisor 链、Tool Calling、设计原则 |
| [安全设计](docs/SECURITY.md) | 双 Token 认证、滑块验证码、RBAC 权限模型 |
| [会话与 ID 设计](docs/CONVERSATION-DESIGN.md) | 雪花 ID、UUIDv7、Conversation 双写架构 |
| [RAG 检索增强](docs/RAG-DESIGN.md) | ETL Pipeline、六阶段检索管道、多租户隔离 |
| [Agentic RAG 设计](docs/AGENTIC-RAG-DESIGN.md) | 意图识别驱动的 Agentic RAG 架构设计 |
| [Agentic RAG 实施](docs/AGENTIC-RAG-IMPLEMENTATION-NOTES.md) | 实施笔记与关键技术决策 |
| [Agentic RAG POC](docs/AGENTIC-RAG-POC-RESULTS.md) | POC 验证结果 |
| [环境配置](docs/CONFIGURATION.md) | Profile 说明、环境变量、完整配置项 |
| [项目结构](docs/PROJECT-STRUCTURE.md) | 完整的源码目录树（文件级） |
| [API 接口文档](docs/API-DOCS.md) | 所有接口的完整说明 |
| [数据库设计文档](docs/DATABASE.md) | 表结构、索引、关系、Redis 使用 |
| [RBAC 用户模块设计](docs/RBAC-USER-MODULE-DESIGN.md) | 权限模型与用户管理 |
| [分片上传设计](docs/design/chunk-upload.md) | 分片上传架构、流程、安全措施 |
| [团队协作功能 PRD](docs/TEAM-FEATURE-PRD.md) | 团队创建/成员管理/审批流/额度控制/权限隔离 |

### 审查报告

| 文档 | 说明 |
|------|------|
| [团队模块审查](docs/TEAM-CODE-REVIEW.md) | 六维审查（3B/7H/8M/4L），全部修复，214 测试全绿 |
| [团队模块审查 (Mimo)](docs/reviews/2026-05-17-team-module-review.md) | Mimo 双模型交叉审查 |
| [Chat 模块审查](docs/reviews/2026-05-18-chat-module-review.md) | 六维深度审查（4B/6H/13M/9L），5 Phase 全部修复 |

**外部参考：** [Spring AI 1.1.6](https://docs.spring.io/spring-ai/docs/1.1.6/api/) · [DeepSeek API](https://api-docs.deepseek.com/) · [智谱 AI API](https://docs.bigmodel.cn/cn/api/introduction) · [MiniMax API](https://platform.minimaxi.com/docs/api-reference/api-overview) · [PGvector 0.8.2](https://github.com/pgvector/pgvector) · [H-RAG (arXiv:2605.00631)](https://arxiv.org/abs/2605.00631)

---

## License

MIT
