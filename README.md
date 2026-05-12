# Chat Demo

基于 **Spring Boot 3.5 + Spring AI 1.1 + MyBatis-Plus 3.5** 的多厂商 AI 聊天助手后端。支持 **DeepSeek、智谱 AI (Zhipu)、MiniMax** 三家模型厂商，通过 Provider 抽象层实现统一路由。提供动态模型加载、SSE 流式响应、JDBC 对话记忆、**Tool Calling 工具调用**、RBAC 权限系统、滑块验证码、自研雪花 ID 与 UUIDv7，并通过 Advisor 链实现限流与内容安全过滤。

支持 **RAG（检索增强生成）**，通过 Apache Tika 多格式文档解析、Parent-Child 分块策略、PGvector 向量存储、阿里千问 text-embedding-v4 向量化，实现文档上传→解析→分块→向量化→检索增强的完整链路。检索管道支持**查询改写、混合检索（向量+BM25）+RRF融合、百炼Rerank精排、MMR多样性重排**四阶段优化。ETL 支持双线程池并发处理，小文档走**快速通道 BM25 即搜即用**（异步向量化补齐）。

## 技术栈

| 依赖 | 版本 | 用途 |
|------|------|------|
| Apache Tika | 随 Spring AI | 多格式文档解析（PDF/DOCX/PPTX/HTML 等） |
| exp4j | 0.4.8 | 安全数学表达式求值（替代 ScriptEngine） |
| Caffeine | 3.x | 本地缓存（SystemPrompt / ModelParams / 验证码） |
| DashScope text-embedding-v4 | - | 阿里千问 Embedding 模型（1024 维） |
| Flyway | 随 Boot | 数据库版本化迁移（schema.sql 初始化） |
| Java | 21 | 运行时 |
| JJWT | 0.13.0 | JWT 双 Token（Access 15min + Refresh 24h） |
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
```

## 文档

| 文档 | 说明 |
|------|------|
| [API 接口文档](docs/API-DOCS.md) | 所有接口的完整说明 |
| [数据库设计文档](docs/DATABASE.md) | 表结构、索引、关系、Redis 使用 |
| [RBAC 用户模块设计](docs/RBAC-USER-MODULE-DESIGN.md) | 权限模型与用户管理 |

**外部参考：** [Spring AI 1.1.6](https://docs.spring.io/spring-ai/docs/1.1.6/api/) · [DeepSeek API](https://api-docs.deepseek.com/) · [智谱 AI API](https://docs.bigmodel.cn/cn/api/introduction) · [MiniMax API](https://platform.minimaxi.com/docs/api-reference/api-overview)

---

## 项目结构

```
src/main/java/com/demo/chat/
├── ChatDemoApplication.java                  # @MapperScan 启动类
│
├── common/                                   # 公共模块
│   ├── snowflake/                            #   自研雪花 ID 生成器
│   │   ├── SnowflakeProperties.java          #     配置：epoch / datacenterId / workerId
│   │   ├── SnowflakeIdGenerator.java         #     核心：64 位雪花算法（线程安全 + 时钟回拨容忍）
│   │   └── SnowflakeConfiguration.java       #     Spring Bean 注册
│   └── uuid/                                 #   UUIDv7 生成器
│       └── UuidV7.java                       #     RFC 9562 — 基于 Unix 毫秒时间戳的有序 UUID
│
├── config/                                   # 基础配置
│   ├── ModelProviderAutoConfiguration.java   #   多厂商自动配置
│   ├── ToolAutoConfiguration.java            #   Tool Calling 自动配置
│   ├── SandboxAutoConfiguration.java         #   沙箱自动配置
│   ├── DeepSeekProperties.java               #   spring.ai.deepseek.*
│   ├── MiniMaxProperties.java                #   spring.ai.minimax.*
│   ├── ZhipuProperties.java                  #   spring.ai.zhipuai.*
│   ├── AdvisorAutoConfiguration.java         #   Advisor 编排 + ChatMemory Bean
│   ├── MyBatisPlusConfig.java                #   分页插件
│   ├── MyBatisPlusMetaHandler.java           #   自动填充（createTime / updateTime）
│   ├── RedisConfig.java                      #   Redis 序列化配置
│   ├── PasswordEncoderConfig.java            #   BCrypt 密码编码器
│   └── TransactionConfig.java                #   TransactionTemplate Bean
│
├── security/                                 # 安全模块
│   ├── config/
│   │   ├── SecurityConfig.java               #   SecurityFilterChain + CORS
│   │   └── JwtProperties.java                #   JWT 配置属性
│   ├── dto/
│   │   └── CaptchaResult.java                #   验证码结果 DTO
│   ├── filter/
│   │   └── JwtAuthenticationFilter.java      #   JWT 认证过滤器
│   ├── service/
│   │   ├── TokenCacheService.java            #   Redis token 存储/吊销/权限缓存/限流
│   │   └── CaptchaService.java               #   滑块拼图验证码（纯 Java 2D，Caffeine 缓存）
│   ├── token/
│   │   └── CookieTokenManager.java           #   Cookie Token 读/写/清除（HttpOnly + SameSite=Lax）
│   └── util/
│       ├── JwtTokenProvider.java             #   JWT 生成/验证 (jti, issuer 校验)
│       └── SecurityUtils.java                #   getCurrentUserId / extractToken
│
├── user/                                     # RBAC 用户模块
│   ├── entity/                               #   数据实体
│   │   ├── SysUser.java                      #     用户表（雪花ID，含 password / status / email）
│   │   ├── SysRole.java                      #     角色表
│   │   ├── SysPermission.java                #     权限表（resource + action）
│   │   ├── SysRolePermission.java            #     角色-权限关联
│   │   └── SysUserRole.java                  #     用户-角色关联
│   ├── enums/UserStatus.java                 #   用户状态枚举
│   ├── mapper/                               #   语义化查询接口 + XML
│   │   ├── SysUserMapper.java                #     selectByUsername / selectActiveById / selectByEmailExcludingId
│   │   ├── SysRoleMapper.java                #     selectAllOrdered / selectByRoleName
│   │   ├── SysPermissionMapper.java          #     selectAllOrdered / selectByPermissionName / selectByResourceKey
│   │   ├── SysRolePermissionMapper.java      #     selectPermissionsByRoleId(s) / deleteByRoleId / batchInsert
│   │   └── SysUserRoleMapper.java            #     selectRoleIdsByUserId / selectUserIdsByRoleId / batchInsert
│   ├── service/                              #   接口层
│   │   ├── AuthService.java                  #     认证：登录/注册/刷新/登出/改密
│   │   ├── SysUserService.java               #     用户 CRUD
│   │   ├── SysRoleService.java               #     角色管理
│   │   └── SysPermissionService.java         #     权限 CRUD
│   ├── service/impl/                         #   实现层（业务编排，不含 SQL）
│   │   ├── AuthServiceImpl.java              #     JWT 双 Token + Redis 吊销 + BCrypt
│   │   ├── SysUserServiceImpl.java           #     用户增删改查 + 密码修改
│   │   ├── SysRoleServiceImpl.java           #     角色增删改查 + 权限分配
│   │   └── SysPermissionServiceImpl.java     #     权限增删改查
│   ├── dto/                                  #   请求/响应 DTO（全部 record）
│   │   ├── LoginRequest.java                 #     登录请求
│   │   ├── LoginResponse.java                #     登录响应（双 Token）
│   │   ├── RegisterRequest.java              #     注册请求
│   │   ├── RefreshRequest.java               #     刷新 Token 请求
│   │   ├── ChangePasswordRequest.java        #     修改密码请求
│   │   ├── UserUpdateRequest.java            #     用户信息更新
│   │   ├── CreateRoleRequest.java            #     创建角色请求
│   │   ├── UpdateRoleRequest.java            #     更新角色请求
│   │   ├── AssignRolesRequest.java           #     分配角色请求
│   │   └── AssignPermissionsRequest.java     #     分配权限请求
│   └── controller/                           #   HTTP 转发
│       ├── AuthController.java               #     /api/auth/*
│       ├── UserController.java               #     /api/users/* (ADMIN)
│       └── RoleController.java               #     /api/roles/* (ADMIN)
│
├── conversation/                             # ★ 会话管理模块（独立于 chat）
│   ├── controller/
│   │   └── ConversationController.java       #     /api/conversations（CRUD + 消息列表）
│   ├── dto/                                  #   数据传输对象
│   │   ├── ConversationCreateRequest.java    #     创建会话请求
│   │   ├── ConversationUpdateRequest.java    #     更新会话请求（标题/置顶/归档）
│   │   ├── ConversationSummary.java          #     会话摘要（列表展示）
│   │   ├── ConversationDetail.java           #     会话详情（含消息树）
│   │   └── MessageVO.java                    #     消息视图对象（含子节点树）
│   ├── entity/                               #   数据实体
│   │   ├── Conversation.java                 #     会话表（UUIDv7 ID + 用户隔离 + 标题/置顶/状态）
│   │   └── Message.java                      #     消息表（树形结构 parent_id + 角色/Token/耗时）
│   ├── enums/                                #   业务枚举（@EnumValue + @JsonValue）
│   │   ├── ConversationStatus.java           #     ACTIVE / ARCHIVED / DELETED
│   │   ├── MessageStatus.java                #     IN_PROGRESS / FINISHED / ERROR
│   │   └── TitleSource.java                  #     SYSTEM / USER
│   ├── mapper/
│   │   ├── ConversationMapper.java           #     原子更新：计数递增/CAS 标题/状态/置顶
│   │   └── MessageMapper.java                #     全量查询 + 物理删除
│   ├── service/                              #   接口层
│   │   ├── ConversationService.java          #     会话 CRUD（面向前端 API）
│   │   └── ConversationMessageService.java   #     消息管理（树构建/保存/删除）
│   ├── service/impl/
│   │   ├── ConversationServiceImpl.java      #     会话管理（UUIDv7 + 并发安全 + 编程式事务）
│   │   └── ConversationMessageServiceImpl.java # 消息树（单次全量查 + 内存分组）
│   └── util/
│       └── ConversationIdUtil.java           #     conversationId 用户隔离工具（u_{userId}_{rawId}）
│
│   │
├── chat/                                     # 聊天核心模块
│   ├── provider/                             #   ★ 多厂商 Provider 抽象层
│   │   ├── ModelProvider.java                #     Provider 接口（策略模式）
│   │   ├── DeepSeekModelProvider.java        #     DeepSeek 实现
│   │   ├── ZhipuModelProvider.java           #     智谱 AI 实现
│   │   ├── MiniMaxModelProvider.java         #     MiniMax 实现
│   │   ├── ProviderRegistry.java             #     厂商注册中心（服务定位模式）
│   │   └── ModelRouter.java                  #     模型 ID 路由解析器
│   │
│   ├── client/
│   │   └── ChatClientRegistry.java           #     ChatClient 注册中心
│   │
│   ├── advisor/                              #   Spring AI Advisor 链
│   │   ├── RateLimiter.java                  #     限流器接口
│   │   ├── TokenBucketLimiter.java           #     令牌桶实现
│   │   ├── RateLimitAdvisor.java             #     限流 Advisor (order=0)
│   │   ├── ContentFilterAdvisor.java         #     内容安全 Advisor (order=1)
│   │   └── ConversationContextAdvisor.java   #     对话上下文注入 Advisor
│   │
│   ├── content/                              #   内容安全
│   │   ├── ContentFilterService.java         #     过滤服务接口
│   │   └── SensitiveWordFilterService.java   #     sensitive-word DFA 实现
│   │
│   ├── mode/                                 #   ★ 对话模式路由（策略模式）
│   │   ├── ChatMode.java                     #     模式枚举（SIMPLE / MULTI_TURN）
│   │   ├── ChatModeStrategy.java             #     策略接口
│   │   ├── SimpleModeStrategy.java           #     单轮模式（无记忆、无思考输出）
│   │   ├── MultiTurnModeStrategy.java        #     多轮模式（ChatMemory + 思考输出）
│   │   └── ModeRouter.java                   #     模式路由器
│   │
│   ├── tool/                                 #   ★ 工具集（模型可调用的工具）
│   │   ├── ToolRegistry.java                 #     工具注册中心（自动发现 @Tool Bean）
│   │   ├── DateTimeTools.java                #     日期时间查询（当前时间、星期、日期差）
│   │   ├── CalculatorTools.java              #     数学计算（表达式求值）
│   │   ├── CodeExecutionTool.java            #     代码执行（Docker 沙箱）
│   │   └── sandbox/                          #     沙箱引擎
│   │       ├── SandboxService.java           #       Docker 容器生命周期管理
│   │       ├── SandboxConfig.java            #       配置属性
│   │       ├── SandboxResult.java            #       执行结果 DTO
│   │       └── Language.java                 #       语言枚举
│   │
│   ├── service/                              #   业务服务（接口层）
│   │   ├── ChatService.java                  #     聊天服务接口（阻塞 + 流式 + 记忆 + 会话集成）
│   │   ├── ModelService.java                 #     模型管理接口（按厂商分组）
│   │   ├── SystemPromptService.java          #     System Prompt 管理接口（Caffeine 缓存）
│   │   ├── PromptLoaderService.java          #     XML 模板加载器接口
│   │   ├── ModelParamsService.java           #     模型参数管理接口（Caffeine 缓存）
│   │   ├── UsageService.java                 #     用量统计接口
│   │   └── ModelRegistryRefresher.java       #     模型注册刷新器（@Component，无接口）
│   │   └── impl/                             #     实现层（业务编排，不含 SQL）
│   │       ├── ChatServiceImpl.java          #     ★ 核心编排（双写事务 + 流式处理 + 回退链）
│   │       ├── ModelServiceImpl.java
│   │       ├── SystemPromptServiceImpl.java
│   │       ├── PromptLoaderServiceImpl.java
│   │       ├── ModelParamsServiceImpl.java
│   │       └── UsageServiceImpl.java
│   │
│   ├── controller/                           #   REST 接口
│   │   ├── ChatController.java               #     /api/chat, /api/models
│   │   ├── PromptController.java             #     /api/prompts
│   │   ├── ModelParamsController.java        #     /api/params
│   │   └── UsageController.java              #     /api/usage
│   │
│   ├── entity/                               #   数据实体
│   │   ├── SystemPrompt.java
│   │   ├── ModelParams.java
│   │   └── TokenUsage.java
│   │
│   ├── mapper/                               #   MyBatis-Plus Mapper（语义化接口 + XML）
│   │   ├── SystemPromptMapper.java           #     selectByModelId / selectAllOrdered / deleteByModelId
│   │   ├── ModelParamsMapper.java            #     selectByModelId / selectAllOrdered / deleteByModelId
│   │   └── TokenUsageMapper.java             #     selectByConversationId / aggregateByModel / ...
│   │
│   └── dto/                                  #   数据传输对象（全部 record）
│       ├── ChatRequest.java
│       ├── ChatResponse.java
│       ├── ModelInfo.java
│       ├── ProviderModelInfo.java
│       ├── ModelsResponse.java
│       ├── ModelParamsDTO.java
│       ├── SystemPromptDTO.java
│       ├── SystemPromptUpdateRequest.java
│       ├── PromptTemplate.java
│       ├── TokenUsageDTO.java
│       ├── UsageStats.java
│       └── ErrorResponse.java
│
├── rag/                                      # ★ RAG 检索增强生成模块
│   ├── config/                               #   RAG 配置
│   │   ├── MinioConfig.java                  #     MinIO Client Bean
│   │   ├── MinioProperties.java              #     spring.minio.* 配置
│   │   ├── DocumentProperties.java           #     app.document.* 配置（MIME 白名单 + 文件大小限制）
│   │   ├── RagRetrievalProperties.java       #     app.rag.* 配置（检索四阶段参数）
│   │   ├── EtlExecutorProperties.java        #     app.etl.executor.* 配置（IO/CPU 线程池参数）
│   │   ├── EtlFastTrackProperties.java       #     app.etl.fast-track.* 配置（快速通道阈值）
│   │   ├── RagConfig.java                    #     检索管道组件组装（混合检索/Rerank/MMR/父文档回查）
│   │   └── RagAdvisorFactory.java            #     按请求动态创建 RAG Advisor（携带 userId 隔离 filter）
│   │
│   ├── retrieval/                            #   ★ 检索优化组件
│   │   ├── HybridDocumentRetriever.java      #     向量 + BM25 混合检索 + RRF 融合
│   │   ├── BailianRerankPostProcessor.java   #     百炼 qwen3-rerank 语义精排
│   │   └── MmrDocumentPostProcessor.java     #     MMR 多样性去重
│   │
│   ├── parser/                               #   ★ 文档解析（策略模式）
│   │   ├── DocumentParser.java               #     解析器接口
│   │   ├── DocumentParserFactory.java        #     MIME 类型路由工厂
│   │   ├── PdfDocumentParser.java            #     PDF 专用（页码元数据）
│   │   ├── MarkdownDocumentParser.java       #     Markdown 专用（标题层级）
│   │   └── TikaDocumentParser.java           #     通用兜底（DOCX/PPTX/HTML...）
│   │
│   ├── chunk/                                #   ★ 自定义分块（策略模式）
│   │   ├── ChunkStrategy.java                #     分块策略接口
│   │   ├── ChunkStrategyFactory.java         #     策略工厂（YAML 配置路由）
│   │   ├── TokenChunkStrategy.java           #     Token 数切分
│   │   ├── StructureAwareChunkStrategy.java  #     结构感知切分（Markdown/PDF/HTML/纯文本自适应）
│   │   ├── ParentChildChunkStrategy.java     #     父子双层切分（默认）
│   │   └── ParentDocumentPostProcessor.java  #     检索后子→父替换 + 去重
│   │
│   ├── etl/                                  #   ★ ETL Pipeline（接口分离 + 并发 + 策略路由）
│   │   ├── Extractor.java                    #     Extract 阶段接口
│   │   ├── Transformer.java                  #     Transform 阶段接口
│   │   ├── Loader.java                       #     Load 阶段接口（含 deleteByDocumentId）
│   │   ├── DocumentExtractor.java            #     MinIO 下载 + Parser 解析
│   │   ├── StrategyTransformer.java          #     路由到 ChunkStrategyFactory
│   │   ├── VectorStoreLoader.java            #     写入 PGvector + 按 documentId 清理
│   │   ├── EtlStatus.java                    #     文档状态常量（PARSING/CHUNKING/COMPLETED...）
│   │   ├── EtlStatusManager.java             #     状态管理器（独立事务，Standard/FastTrack 共享）
│   │   ├── EtlCandidate.java                 #     ETL 候选文档 record
│   │   ├── EtlResult.java                    #     ETL 结果 record
│   │   ├── EtlRouteStrategy.java             #     策略接口
│   │   ├── EtlRouteStrategyFactory.java      #     自动发现策略 Bean + order 排序
│   │   ├── EtlExecutorConfig.java            #     线程池 Bean 注册
│   │   ├── EtlTaskExecutorBridge.java        #     执行器门面
│   │   ├── StandardStrategy.java             #     标准并发策略（IO→CPU→IO）
│   │   └── FastTrackStrategy.java            #     快速通道（BM25 先行 + 异步向量化）
│   │
│   ├── embedding/                            #   ★ 向量化
│   │   ├── DashScopeEmbeddingProperties.java #     DashScope 配置
│   │   ├── DashScopeEmbeddingApi.java        #     API 请求/响应 DTO
│   │   └── DashScopeEmbeddingModel.java      #     Spring AI EmbeddingModel 实现（空文本防护 + 30s 超时）
│   │
│   ├── service/                              #   编排服务
│   │   ├── DocumentApplicationService.java   #     文档应用服务接口（SRP：Controller 仅做 HTTP 层）
│   │   ├── FileStorageService.java           #     文件存储接口（MinIO/S3/本地）
│   │   ├── EtlPipelineService.java           #     ETL Pipeline 接口（直接调用场景）
│   │   ├── EtlDispatchService.java           #     ETL 调度服务接口（路由 + 并发编排入口）
│   │   └── impl/
│   │       ├── DocumentApplicationServiceImpl.java  # 文档业务门面（校验/上传/查询/删除 + owner 校验）
│   │       ├── MinioFileStorageService.java  #     MinIO 实现（上传/下载/删除/预签名）
│   │       ├── EtlPipelineServiceImpl.java   #     纯编排器（委托 EtlStatusManager）
│   │       └── EtlDispatchServiceImpl.java   #     调度实现（StrategyFactory 路由）
│   │
│   ├── controller/
│   │   └── DocumentController.java           #     /api/documents/* (上传/列表/详情/删除/状态)
│   │
│   ├── entity/
│   │   └── RagDocument.java                  #     文档记录（含 userId + 状态机: UPLOADED→...→COMPLETED/FAILED）
│   │
│   ├── dto/
│   │   ├── DocumentDTO.java                  #     文档详情 DTO
│   │   └── DocumentUploadResponse.java       #     上传响应 DTO
│   │
│   └── mapper/
│       └── RagDocumentMapper.java            #     MyBatis-Plus Mapper
│
└── exception/                                # 异常处理
    ├── GlobalExceptionHandler.java           #   统一错误响应 (400/401/403/404/429/500)
    ├── BusinessException.java                #   业务异常（统一替代 IllegalArgumentException）
    ├── ContentFilteredException.java         #   内容过滤异常
    ├── ModelNotFoundException.java           #   模型不存在异常
    ├── ProviderNotFoundException.java        #   厂商不存在异常
    └── RateLimitExceededException.java       #   限流异常

resources/
├── application.yml / application-{profile}.yml
├── db/migration/                             #   Flyway 数据库迁移
│   ├── V1__init_schema.sql                   #     完整表结构（幂等）
│   ├── V2__vector_store_bm25.sql             #     BM25 全文检索（tsvector + GIN 索引）
│   ├── V5__conversation_and_message.sql      #     会话 + 消息表
│   └── V6__backfill_conversation_and_message.sql # 历史对话数据回填
├── static/prompt/                            # XML System Prompt 模板
│   ├── default.xml
│   ├── deepseek-chat.xml
│   └── deepseek-reasoner.xml
└── mapper/                                   # MyBatis XML Mapper（SQL 全部由 XML 维护）
    ├── SysUserMapper.xml
    ├── SysRoleMapper.xml
    ├── SysPermissionMapper.xml
    ├── SysUserRoleMapper.xml
    ├── SysRolePermissionMapper.xml
    ├── TokenUsageMapper.xml
    ├── ConversationMapper.xml               #     会话查询
    ├── MessageMapper.xml                     #     消息全量查询/物理删除
    ├── ModelParamsMapper.xml
    ├── SystemPromptMapper.xml
    └── rag/RagDocumentMapper.xml
```

## 架构设计

### 1. 多厂商 Provider 抽象（核心架构）

```
ChatController
     │
     ▼
ChatService ─── ModelRouter.resolve("deepseek/deepseek-chat") → Route("deepseek", "deepseek-chat")
     │                                                              │
     │                    ProviderRegistry.get("deepseek")           │
     │                              │                               │
     │                              ▼                               │
     │              ┌──────── ModelProvider (interface) ────────┐   │
     │              │                  │                        │   │
     │     DeepSeekProvider   ZhipuProvider          MiniMaxProvider
     │              │                │                       │      │
     │              ▼                ▼                       ▼      │
     │         DeepSeekApi     ZhiPuAiApi             MiniMaxApi   │
     │              │                │                       │      │
     │              ▼                ▼                       ▼      │
     │         ChatClient      ChatClient             ChatClient   │
     │              │                │                       │      │
     │              └───────┬────────┴──────────┬───────────┘      │
     │                      ▼                   ▼                   │
     │             ChatClientRegistry ← ModelRegistryRefresher     │
     │                                                              │
     ▼                                                              │
  ChatClient.call(prompt) ──→ 流式/阻塞响应                         │
```

**关键设计：**
- **策略模式**：`ModelProvider` 接口封装所有厂商差异（API 调用、ChatOptions 类型、模型列表获取）
- **服务定位模式**：`ProviderRegistry` 通过 Spring 构造器注入自动发现所有 Provider 实现
- **开闭原则 (OCP)**：新增厂商只需新增 Provider 实现类 + Properties record + RestClient Bean，零修改现有代码
- **容错启动**：未配置 API Key 的 Provider 静默跳过，不影响其他 Provider 和服务启动

### 2. 模型 ID 路由

```
请求 model="deepseek/deepseek-chat"  → Route(providerId="deepseek", modelId="deepseek-chat")
请求 model="zhipu/glm-4.7"          → Route(providerId="zhipu", modelId="glm-4.7")
请求 model="minimax/MiniMax-M2.1"    → Route(providerId="minimax", modelId="MiniMax-M2.1")
请求 model="deepseek-chat"           → Route(providerId="deepseek", modelId="deepseek-chat")  // 向后兼容
```

- `providerId/modelId` 复合格式精确路由到指定厂商
- 无前缀时回退到默认厂商（`model.router.default-provider`，默认 `deepseek`）

### 3. 用户隔离

所有对话和用量数据通过 `ConversationIdUtil` 自动附加用户前缀：

```
用户 42 请求 conversationId="test" → 存储 "u_42_test"
```

- Controller/Service 通过 `ConversationIdUtil.buildIsolatedId()` 统一构建
- 对外 API 透明：请求/响应始终使用原始 ID，内部自动隔离
- 用量统计查询通过 LIKE 前缀过滤当前用户数据

### 4. 双 Token 认证

```
登录 → Access Token (15min) + Refresh Token (24h)
      ↓
请求 → JwtAuthenticationFilter
      ↓ JWT 验证 + Redis 吊销检查 + 用户状态检查
      ↓ 权限加载（Redis 缓存 300s → DB fallback）
      ↓
@PreAuthorize("hasAuthority('chat:send')")
```

- JWT 载荷：`sub` (userId), `jti` (UUID), `roles`, `type`, `iss`
- 权限从 Redis/DB 动态查询，支持实时变更
- 改密/禁用/删除用户时自动吊销所有 Token

### 5. 滑块验证码

```
GET /api/auth/captcha → {captchaId, backgroundImage, puzzleImage, answer(dev only)}
                                    ↓
前端拖动拼图块 → POST /api/auth/login 携带 captchaId + captchaCode
                                    ↓
后端校验：Caffeine 缓存取答案，±5px 容差，一次性使用
```

- **纯 Java 2D 实现**，无外部图片/模型依赖
- dev 环境返回 answer 坐标，方便 API 测试；其他环境不返回

### 6. 自研雪花 ID（仅 SysUser）

```
┌─────────────────────────────────────────────────────────────────┐
│ 0 (1b) │ timestamp (41b) │ datacenterId (5b) │ workerId (5b) │ seq (12b) │
└─────────────────────────────────────────────────────────────────┘
```

| 特性 | 说明 |
|------|------|
| 自定义纪元 | 默认 2026-01-01，可用约 69 年 |
| datacenterId + workerId | 10 位，最多 1024 实例 |
| 时钟回拨容忍 | ≤5ms 等待恢复 |
| 线程安全 | ReentrantLock |
| 吞吐量 | 每秒 409.6 万 |

### 6.5. Conversation 模块（独立于 chat）

```
┌─────────────────── chat 模块 ───────────────────┐
│                                                 │
│  ChatServiceImpl                                │
│    │                                            │
│    ├─ ensureConversationExists() ──────────────┐│
│    │   (getOrCreate, 并发安全)                  ││
│    │                                           ││
│    ├─ saveMessagesAndNotify() ────────────────┐││
│    │   TransactionTemplate:                    │││
│    │     1. saveMessage(USER)                  │││
│    │     2. saveMessage(ASSISTANT)             │││
│    │     3. onNewMessages(count+title)         │││
│    │                                           ▼▼▼
│    │                              ┌── conversation 模块 ──┐
│    │                              │ ConversationService   │
│    │                              │   ├─ create (UUIDv7)  │
│    │                              │   ├─ getOrCreate      │
│    │                              │   ├─ update/delete    │
│    │                              │   └─ onNewMessages    │
│    │                              │                      │
│    │                              │ ConversationMsgSvc   │
│    │                              │   ├─ buildMsgTree    │
│    │                              │   │  (全量查+内存分组) │
│    │                              │   ├─ saveMessage      │
│    │                              │   └─ deleteByConvId   │
│    │                              └──────────────────────┘
│    │
│    └─ Spring AI ChatMemory ──→ spring_ai_chat_memory 表
│                               (独立于 message 表)
└─────────────────────────────────────────────────────────┘
```

**双写架构：**

| 层 | 存储 | 用途 |
|----|------|------|
| Spring AI | `spring_ai_chat_memory` | 多轮对话历史，框架自动管理 |
| 业务层 | `message` 表 | 消息树（分支/重新生成）+ Token 用量 + 耗时 |
| 业务层 | `conversation` 表 | 会话元数据（标题/置顶/状态/计数） |

**关键设计：**

- **conversationId = `u_{userId}_{uuidv7}`**：用户隔离 + 时间有序
- **UUIDv7 (RFC 9562)**：48 位毫秒时间戳 + 74 位随机数，自实现零依赖
- **消息树**：`parent_id` 构建树形，一次全量查 + 内存 `groupingBy(parentId)` 分组（禁止 N+1）
- **并发安全**：`getOrCreate` 依赖唯一约束 + catch `DuplicateKeyException` 重查
- **编程式事务**：`TransactionTemplate` 保证 USER+ASSISTANT 消息原子写入
- **CAS 标题**：首次消息时原子设置标题（`WHERE message_count = 0 AND title_source = 'SYSTEM'`）
- **枚举映射**：`@EnumValue` + `@JsonValue`，实体用枚举不用 String

**依赖方向：**

```
chat → conversation → (security, common)
  ↑ 独立       ↑ 独立于 rag、user
```

### 7. RBAC 权限模型

```
sys_user ─< sys_user_role >─ sys_role ─< sys_role_permission >─ sys_permission
```

| 权限码 | ADMIN | USER |
|--------|-------|------|
| `chat:send` | ✅ | ✅ |
| `chat:stream` | ✅ | ✅ |
| `conversation:manage` | ✅ | ✅ |
| `usage:view` | ✅ | ✅ |
| `model:config` | ✅ | ❌ |
| `prompt:manage` | ✅ | ❌ |
| `user:manage` | ✅ | ❌ |
| `role:manage` | ✅ | ❌ |

### 8. Advisor 链

```
请求 → ConversationContextAdvisor (order=-1, 注入 conversationId)
     → RateLimitAdvisor (order=0, 限流)
     → ContentFilterAdvisor (order=1, 输入检测)
     → RetrievalAugmentationAdvisor (ragEnabled=true 时由 RagAdvisorFactory 动态创建，携带 userId 隔离)
     → ToolCallAdvisor (order=2, 工具调用循环)
     → MessageChatMemoryAdvisor (对话记忆写入)
     → 模型调用
     → ContentFilterAdvisor (after, 输出过滤)
     → 响应
```

### 9. Tool Calling（工具调用）

```
用户提问 "明天星期几？"
     │
     ▼ 模型决定需要调用工具
     │
ToolCallAdvisor → ToolCallingManager
     │
     ▼ 分发到 DateTimeTools.getCurrentWeekday(offsetDays=1)
     │
     ▼ 工具返回 "2026-05-10 是星期日"
     │
     ▼ 结果回传模型，生成最终回复
```

- **声明式注册**：`@Component` + `@Tool` 注解，Spring 自动发现
- **OCP**：新增工具 = 新增类，零改现有代码
- **ToolCallAdvisor** 显式在 advisor 链中处理，限流/内容过滤可拦截工具调用过程
- **disableMemory()**：已有 MessageChatMemoryAdvisor 管理对话历史，避免重复
- 内置工具：`DateTimeTools`（日期时间查询）、`CalculatorTools`（数学计算，基于 exp4j）、`CodeExecutionTool`（沙箱代码执行）

#### 沙箱代码执行

```
模型返回 tool_call: { code: "print(sum(range(1, 101)))", language: "python" }
     │
     ▼ CodeExecutionTool.executeCode()
     │
     ▼ SandboxService
     │  docker create --rm --network=none --read-only --user nobody
     │           --memory=128m --cpus=1 --pids-limit=64
     │           sandbox-python:bookworm timeout 10 python3 /tmp/code.py
     │
     ▼ 执行完毕，容器自动删除
     │
     ▼ 返回结果 "退出码: 0\n输出:\n5050"
     │
     ▼ 模型生成最终回复："1 到 100 的和是 5050"
```

| 安全层级 | 措施 |
|---------|------|
| 网络 | `--network=none` |
| 文件系统 | `--read-only` + `tmpfs /tmp` |
| 用户 | `--user nobody` |
| 内存 | `--memory=128m` |
| CPU | `--cpus=1` |
| 进程 | `--pids-limit=64` |
| 超时 | `timeout 10` + Java Future 双重保障 |
| 清理 | `--rm` 用完即弃 |

### 10. 数据访问分层

```
Controller (@Valid 校验)
    ↓ 注入 Service 接口
Service 接口
    ↓
ServiceImpl (业务编排，不含 SQL)
    ↓
Mapper (语义化接口 + XML，所有查询逻辑在此)
    ↓
Database
```

- **Service 层接口/实现分离**：chat 模块与 user 模块一致，Controller 注入接口，实现类独立维护
- **Service 层不含 `LambdaQueryWrapper`**：所有查询通过 Mapper 语义化方法（`selectByModelId`、`selectAllOrdered`）或 XML SQL 实现
- **Mapper SQL 全部由 XML 维护**：不使用 `@Select`/`@Delete` 注解，SQL 集中在 `resources/mapper/*.xml`
- **编程式事务** `TransactionTemplate`，不使用 `@Transactional`
- DTO 全部使用 Java record，Entity 不暴露给前端
- **Flyway 数据库迁移**：`V1__init_schema.sql` 全量建表 + `V2__vector_store_bm25.sql` BM25 支持

### 11. 缓存策略

| 层级 | 技术 | TTL | 场景 |
|------|------|-----|------|
| 本地 | Caffeine | 30s | ModelParams 热路径 |
| 本地 | Caffeine | 5min | SystemPrompt / 验证码答案 |
| 分布式 | Redis | 300s | 用户权限缓存 |
| 分布式 | Redis | 900s | Access Token 元数据 |
| 分布式 | Redis | 86400s | Refresh Token + 用户状态标记 |

### 12. RAG 检索增强生成

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

### 13. RAG 多租户隔离

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

## 环境配置

### Profile 说明

| Profile | 用途 | 验证码 answer |
|---------|------|--------------|
| `dev` | 本地开发 | ✅ 返回 |
| `stable` | 测试/预发 | ❌ 不返回 |
| `prod` | 生产（叠加 stable） | ❌ 不返回 |

启动：`--spring.profiles.active=stable,prod`

### 关键配置项

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
      io-pool-core-size: 4
      io-pool-max-size: 8
      io-queue-capacity: 50
      cpu-pool-core-size: 2
      cpu-pool-max-size: 4
      cpu-queue-capacity: 20
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

## 异常处理

| 异常 | HTTP 状态码 | 场景 |
|------|------------|------|
| `RateLimitExceededException` | 429 | 登录限流 / API 限流 |
| `AuthenticationException` | 401 | 未认证 / Token 失效 |
| `AccessDeniedException` | 403 | 权限不足 |
| `ContentFilteredException` | 400 | 敏感词 |
| `ModelNotFoundException` | 404 | 模型不存在 |
| `ProviderNotFoundException` | 404 | 厂商不存在或未配置 |
| `BusinessException` | 400 | 业务逻辑错误 |
| `MethodArgumentNotValidException` | 400 | 参数校验失败 |
| 通用 `Exception` | 500 | 服务内部错误 |

## 设计原则

| 原则 | 实践 |
|------|------|
| **单一职责 (SRP)** | Controller 只做 HTTP 转发、Service 只做业务编排、Mapper 只做数据访问 |
| **依赖倒置 (DIP)** | 依赖接口（`AuthService`、`ChatService`、`RateLimiter`、`ContentFilterService`），不依赖实现类 |
| **开闭原则 (OCP)** | 新增厂商 = 新增 Provider 类；新增限流算法 = 实现 `RateLimiter` 接口；零改旧代码 |
| **接口隔离 (ISP)** | 每个 Service 提供独立接口（chat 模块与 user 模块一致），Controller 按需注入 |
| **策略模式** | `ModelProvider` 封装厂商差异，`ProviderRegistry` 自动发现 |
| **DTO 隔离** | Entity 不暴露给前端，全部通过 record DTO 转换 |
| **数据访问下沉** | `LambdaQueryWrapper` 全部在 Mapper 层，Service 层不含 SQL 构建逻辑 |
| **编程式事务** | `TransactionTemplate` 精确控制事务边界 |
| **安全纵深** | JWT + Redis 吊销 + 用户状态 + IP 限流 + 滑块验证码 + Cookie SameSite + 资源级 owner 校验 |
| **自研核心** | 雪花 ID 生成器、UUIDv7 (RFC 9562) 生成器、滑块验证码均为纯 Java 实现，无外部依赖 |
| **模板方法 + 接口分离** | ETL Pipeline 拆分为 Extractor/Transformer/Loader 独立接口，Pipeline 只做编排 |
| **四阶段检索管道** | 查询改写→混合检索+RRF→Rerank→MMR，各阶段独立可配，管道通过 `RagConfig` 统一装配 |
| **双层检索（Parent-Child）** | 子切分保证检索精度，父文档保证 LLM 上下文完整性 |
| **并发 ETL + 策略路由** | IO/CPU 双线程池分离，FastTrack/Standard 策略路由，OCP 零改旧代码 |
| **ETL 状态集中管理** | EtlStatus 常量类 + EtlStatusManager 独立事务，消除重复代码 |
| **EmbeddingModel 接口适配** | 自建 DashScopeEmbeddingModel 实现标准接口，PgVectorStore 自动注入，零耦合 |

## License

MIT
