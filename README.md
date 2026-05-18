# Chat Demo

基于 **Spring Boot 3.5 + Spring AI 1.1 + MyBatis-Plus 3.5** 的多厂商 AI 聊天助手后端。支持 **DeepSeek、智谱 AI (Zhipu)、MiniMax** 三家模型厂商，通过 Provider 抽象层实现统一路由与自动降级。提供动态模型加载、SSE 流式响应、JDBC 对话记忆、**Tool Calling 工具调用**、RBAC 权限系统、滑块验证码、自研雪花 ID 与 UUIDv7，并通过 Advisor 链实现限流与内容安全过滤。

## RAG 检索增强生成

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
- **MinIO BucketResolver**：个人/团队文档 bucket 隔离
- **编码自动检测**：文本/Markdown 上传自动识别 GBK/GB2312/GB18030 编码并转码为 UTF-8

## 评估系统

独立的 RAG 评估框架（`@Profile("evaluation")`，dev 环境零侵入）：
- **检索侧**：召回率 (Recall@K)、准确率 (Precision@K)、MRR、NDCG
- **生成侧**：忠实度 (Faithfulness)、答案相关性、上下文相关性、上下文召回率
- **Judge 模型**：独立于 Provider 路由，通过 `application-evaluation.yml` 直连厂商 API

## 团队协作

团队创建与解散、成员管理（邀请/移除/角色变更）、上传审批流、额度控制、文档权限隔离、团队分片上传。

## 技术栈

| 依赖 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 运行时 |
| Spring Boot | 3.5.14 | 应用框架 |
| Spring AI | 1.1.6 | AI 模型集成（DeepSeek / 智谱 / MiniMax） |
| MyBatis-Plus | 3.5.16 | ORM 框架 |
| PostgreSQL | 18 | 主数据库 |
| PGvector | 0.8.2 | 向量数据库（HNSW 索引） |
| pg_jieba | - | 中文分词（BM25 全文检索） |
| Redis | 8.2 | 缓存 / Token 存储 / 权限缓存 |
| MinIO | 9.0.0 | 对象存储（文档管理） |
| Flyway | 随 Boot | 数据库版本化迁移 |
| Spring Security | 随 Boot | JWT 双 Token 认证 + RBAC 授权 |
| JJWT | 0.13.0 | JWT 双 Token（Access 15min + Refresh 24h） |
| Apache Tika | 随 Spring AI | 多格式文档解析（PDF/DOCX/PPTX/HTML 等） |
| juniversalchardet | 2.5.0 | 文本编码自动检测（GBK/GB2312/GB18030） |
| DashScope text-embedding-v4 | - | 阿里千问 Embedding 模型（1024 维） |
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

# Flyway 自动执行数据库迁移（共 13 个版本）：
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

# RAG（可选）
export DASHSCOPE_API_KEY=sk-***       # 阿里千问 Embedding + Rerank

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
java -jar target/chat-demo-0.0.1-SNAPSHOT.jar
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

# 4. 聊天 — 复合格式指定厂商
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" -b cookies.txt \
  -d '{"model":"deepseek/deepseek-chat","message":"你好","conversationId":"test"}'

# 5. SSE 流式聊天
curl "http://localhost:8080/api/chat/stream?model=zhipu/glm-4.7&message=你好&conversationId=test" \
  -b cookies.txt

# 6. 上传文档（RAG，支持 GBK/GB2312/GB18030 自动转码）
curl -X POST http://localhost:8080/api/documents/upload \
  -b cookies.txt -F "file=@document.pdf"

# 7. RAG 增强聊天
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" -b cookies.txt \
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

## 项目结构

```
src/main/java/com/demo/chat/
├── common/              # 公共模块（错误码、分页、雪花ID、UUIDv7、工具类）
├── config/              # 基础配置（多厂商、Advisor、MyBatis-Plus、Redis）
├── security/            # 安全模块（JWT 双Token、验证码、Spring Security）
├── user/                # RBAC 用户模块（用户/角色/权限 CRUD、BCrypt）
├── conversation/        # 会话管理（独立于 chat，双写架构）
├── chat/                # 聊天核心（Provider 抽象、模型路由、Advisor 链、Tool Calling、自动降级）
├── rag/                 # RAG 模块
│   ├── config/          #   RAG 配置（RagRetrievalProperties record、RagAdvisorFactory）
│   ├── retrieval/       #   检索（HybridDocumentRetriever、Rerank、MMR、QueryNormalizer）
│   ├── chunk/           #   分块（Parent-Child 策略、ParentDocumentPostProcessor 含 Rescoring）
│   ├── embedding/       #   向量化（DashScopeEmbeddingModel、批量分片、场景识别）
│   ├── etl/             #   ETL Pipeline（双线程池、Standard/FastTrack 策略路由）
│   ├── mapper/          #   数据访问（VectorStoreMapper: BM25/ParentChild/Cosine距离）
│   ├── evaluation/      #   评估系统（@Profile("evaluation"), Judge/LlmScorer/指标计算）
│   ├── parser/          #   文档解析（Apache Tika + 编码自动检测）
│   ├── upload/          #   上传策略（个人上传）
│   └── entity/          #   RAG 实体
├── team/                # 团队协作（团队/成员/审批/额度/权限隔离/团队上传）
└── exception/           # 统一异常处理
```

> 完整的文件级目录结构见 [项目结构文档](docs/PROJECT-STRUCTURE.md)。

## 文档

### 设计文档

| 文档 | 说明 |
|------|------|
| [架构设计](docs/ARCHITECTURE.md) | Provider 抽象、模型路由、Advisor 链、Tool Calling、设计原则 |
| [安全设计](docs/SECURITY.md) | 双 Token 认证、滑块验证码、RBAC 权限模型 |
| [会话与 ID 设计](docs/CONVERSATION-DESIGN.md) | 雪花 ID、UUIDv7、Conversation 双写架构 |
| [RAG 检索增强](docs/RAG-DESIGN.md) | ETL Pipeline、六阶段检索管道、多租户隔离 |
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
