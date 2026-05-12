# 项目结构

> 完整的源码目录结构，包含每个文件/类的职责说明。

## 源码结构


```
src/main/java/com/demo/chat/
├── ChatDemoApplication.java                  # @MapperScan 启动类
│
├── common/                                   # 公共模块
│   ├── errorcode/                            #   结构化错误码
│   │   └── ErrorCode.java                    #     46 个错误码枚举（6 大模块分段 10xxx~50xxx）
│   ├── request/                              #   通用请求封装
│   │   └── PageRequest.java                  #     分页参数（page/size 校验 + toPage()）
│   ├── response/                             #   通用响应封装
│   │   ├── GlobalResponse.java               #     统一响应包装（code/message/data）
│   │   └── PagedResult.java                  #     分页结果（content/page/size/total/totalPages）
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
│   │   ├── UserVO.java                       #     用户视图对象（替代 Map）
│   │   ├── UserStatusUpdateResult.java       #     状态更新结果
│   │   ├── RoleAssignResult.java             #     角色分配结果
│   │   ├── UserDeleteResult.java             #     用户删除结果
│   │   ├── RoleDetailVO.java                 #     角色详情（含权限列表）
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
│       └── UsageStats.java
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
│   ├── upload/                              #   ★ 分片上传（秒传 + 断点续传 + 异步合并）
│   │   ├── ChunkUploadController.java        #     /api/documents/multipart/* (5 个端点)
│   │   ├── ChunkUploadService.java           #     分片上传接口
│   │   ├── ChunkUploadServiceImpl.java       #     实现（session/分片/合并/秒传/速率限制）
│   │   ├── OrphanChunkCleaner.java           #     孤儿分片定时清理（6h 间隔，48h 阈值）
│   │   ├── UploadRedisConstants.java         #     Redis key 前缀 + TTL 常量
│   │   ├── ChunkSizeStrategy.java            #     分片大小策略接口
│   │   ├── DefaultChunkSizeStrategy.java     #     默认 5MB
│   │   ├── ChunkUploadInitRequest.java       #     init 请求 DTO (record + @Valid)
│   │   ├── ChunkUploadCompleteRequest.java   #     complete 请求 DTO
│   │   ├── ChunkUploadCompleteResult.java    #     complete 结果 DTO
│   │   └── ChunkUploadStatusResponse.java    #     status 响应 DTO
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

## 资源文件结构

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
