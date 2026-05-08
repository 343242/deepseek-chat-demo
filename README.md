# Chat Demo

基于 **Spring Boot 3 + Spring AI 1.x + MyBatis-Plus** 的多厂商 AI 聊天助手后端。支持 **DeepSeek、智谱 AI、MiniMax** 三家模型厂商，通过 Provider 抽象层实现统一路由。提供动态模型加载、SSE 流式响应、JDBC 对话记忆、RBAC 用户权限系统、滑块验证码、自定义雪花 ID，并通过 Advisor 链实现限流与内容安全过滤。

## 技术栈

| 依赖 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 运行时 |
| Spring Boot | 3.5.14 | 应用框架 |
| Spring AI | 1.1.5 | AI 模型集成 |
| spring-ai-starter-model-deepseek | 1.1.5 | DeepSeek 模型接入 |
| spring-ai-starter-model-zhipuai | 1.1.5 | 智谱 AI 模型接入 |
| spring-ai-starter-model-minimax | 1.1.5 | MiniMax 模型接入 |
| MyBatis-Plus | 3.5.16 | ORM 框架 |
| Spring Security | 随 Boot | 认证与授权 |
| JJWT | 0.12.6 | JWT 双 Token（Access 15min + Refresh 24h） |
| Spring Data Redis | 随 Boot | Token 存储、权限缓存、IP 限流 |
| Caffeine | 3.x | 本地缓存（SystemPrompt / ModelParams / 验证码） |
| sensitive-word | 0.29.5 | DFA 敏感词过滤（纯内存，14W+ QPS） |
| PostgreSQL | 18 | 主数据库 |
| Redis | 8.2 | 缓存 / Token 存储 |

## 快速开始

### 1. 启动依赖服务

```bash
# PostgreSQL
docker run -d --name chat-demo-pg \
  --restart unless-stopped \
  -e POSTGRES_DB=chat_demo \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=*** \
  -p 5432:5432 \
  postgres:18-bookworm

# Redis
docker run -d --name chat-demo-redis \
  --restart unless-stopped \
  -p 6379:6379 \
  redis:8.2-bookworm redis-server --appendonly yes
```

### 2. 配置环境变量

```bash
# 至少配置一个厂商的 API Key
export DEEPSEEK_API_KEY=***
export ZHIPU_API_KEY=***       # 可选
export MINIMAX_API_KEY=***     # 可选

export JWT_SECRET=your-j…ng!!
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

> 首次部署请先执行建表脚本：`psql -U postgres -d chat_demo -f sql/schema.sql`
> 初始管理员账号：`admin` / `admin123`（生产环境请立即修改）

### 4. 验证

```bash
# 获取验证码（dev 环境会返回 answer）
CAPTCHA=$(curl -s http://localhost:8080/api/auth/captcha)
echo $CAPTCHA

# 登录（需要验证码）
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"***","captchaId":"<captchaId>","captchaCode":"<answer>"}'

# 查看可用模型（按厂商分组）
curl http://localhost:8080/api/models \
  -H "Authorization: Bearer <accessToken>"

# 聊天 — 复合格式指定厂商
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <accessToken>" \
  -d '{"model":"deepseek/deepseek-chat","message":"你好","conversationId":"test"}'

# SSE 流式聊天
curl http://localhost:8080/api/chat/stream?model=zhipu/glm-4.7&message=你好&conversationId=test \
  -H "Authorization: Bearer <accessToken>"
```

## 文档

### 项目文档

- [API 接口文档](docs/API-DOCS.md) — 所有接口的完整说明
- [数据库设计文档](docs/DATABASE.md) — 表结构、索引、关系、Redis 使用
- [RBAC 用户模块设计](docs/RBAC-USER-MODULE-DESIGN.md) — 权限模型与用户管理

### 外部参考文档

| 文档 | 链接 |
|------|------|
| Spring AI API 1.1.6 | <https://docs.spring.io/spring-ai/docs/1.1.6/api/> |
| DeepSeek API | <https://api-docs.deepseek.com/> |
| 智谱 AI (BigModel) API | <https://docs.bigmodel.cn/cn/api/introduction> |
| MiniMax API | <https://platform.minimaxi.com/docs/api-reference/api-overview> |

---

## 项目结构

```
src/main/java/com/demo/chat/
├── ChatDemoApplication.java                  # @MapperScan 启动类
│
├── common/                                   # 公共模块
│   └── snowflake/                            #   自研雪花 ID 生成器
│       ├── SnowflakeProperties.java          #     配置：epoch / datacenterId / workerId
│       ├── SnowflakeIdGenerator.java         #     核心：64 位雪花算法（线程安全 + 时钟回拨容忍）
│       └── SnowflakeConfiguration.java       #     Spring Bean 注册
│
├── config/                                   # 基础配置
│   ├── ModelProviderAutoConfiguration.java   #   多厂商自动配置：Properties 绑定 + RestClient Bean + 模型初始化
│   ├── DeepSeekProperties.java               #   DeepSeek 配置属性 (spring.ai.deepseek.*)
│   ├── MiniMaxProperties.java                #   MiniMax 配置属性 (spring.ai.minimax.*)
│   ├── ZhipuProperties.java                 #   智谱 AI 配置属性 (spring.ai.zhipuai.*)
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
│   │   └── CookieTokenManager.java           #   Cookie Token 读/写/清除（SRP 抽取）
│   └── util/
│       ├── JwtTokenProvider.java             #   JWT 生成/验证 (jti, issuer 校验)
│       └── SecurityUtils.java                #   getCurrentUserId / extractToken
│
├── user/                                     # RBAC 用户模块
│   ├── entity/                               #   SysUser(雪花ID), SysRole, SysPermission, ...
│   ├── enums/
│   │   └── UserStatus.java                   #   用户状态枚举
│   ├── mapper/                               #   语义化查询接口 + XML（SRP：数据访问层）
│   │   ├── SysUserMapper.java                #     selectByUsername / selectActiveById / selectByEmailExcludingId
│   │   ├── SysRoleMapper.java                #     selectAllOrdered / selectByRoleName
│   │   ├── SysPermissionMapper.java          #     selectAllOrdered / selectByPermissionName / selectByResourceKey
│   │   ├── SysRolePermissionMapper.java      #     selectPermissionsByRoleId(s) / deleteByRoleId / batchInsert
│   │   └── SysUserRoleMapper.java            #     selectRoleIdsByUserId / selectUserIdsByRoleId / batchInsert
│   ├── service/                              #   接口层（面向 Controller）
│   │   ├── AuthService.java                  #     认证：登录/注册/刷新/登出/改密
│   │   ├── SysUserService.java               #     用户 CRUD
│   │   ├── SysRoleService.java               #     角色管理
│   │   ├── SysPermissionService.java         #     权限 CRUD
│   │   └── impl/                             #     实现层（业务编排，不含 SQL）
│   │       ├── AuthServiceImpl.java
│   │       ├── SysUserServiceImpl.java
│   │       ├── SysRoleServiceImpl.java
│   │       └── SysPermissionServiceImpl.java
│   ├── dto/                                  #   Login/Register/Refresh/ChangePassword/...
│   │   ├── LoginRequest.java
│   │   ├── RegisterRequest.java
│   │   ├── RefreshRequest.java
│   │   ├── ChangePasswordRequest.java
│   │   ├── LoginResponse.java
│   │   ├── UserUpdateRequest.java
│   │   ├── AssignRolesRequest.java
│   │   ├── AssignPermissionsRequest.java
│   │   ├── CreateRoleRequest.java            #     角色创建 DTO（替代 Map<String,String>）
│   │   └── UpdateRoleRequest.java            #     角色更新 DTO
│   └── controller/                           #   仅负责 HTTP 转发（SRP）
│       ├── AuthController.java               #     /api/auth/*（Cookie 委托 CookieTokenManager）
│       ├── UserController.java               #     /api/users/* (ADMIN)
│       └── RoleController.java               #     /api/roles/* (ADMIN)
│
├── chat/                                     # 聊天核心模块
│   │
│   ├── provider/                             #   ★ 多厂商 Provider 抽象层
│   │   ├── ModelProvider.java                #     Provider 接口（策略模式）
│   │   ├── DeepSeekModelProvider.java        #     DeepSeek Provider 实现
│   │   ├── ZhipuModelProvider.java           #     智谱 AI Provider 实现
│   │   ├── MiniMaxModelProvider.java         #     MiniMax Provider 实现
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
│   ├── service/                              #   业务服务
│   │   ├── ChatService.java                  #     聊天服务（阻塞 + 流式 + 记忆 + doFinally）
│   │   ├── ModelService.java                 #     模型管理（按厂商分组）
│   │   ├── ModelRegistryRefresher.java       #     模型注册刷新器（遍历所有 Provider）
│   │   ├── ConversationService.java          #     对话管理
│   │   ├── SystemPromptService.java          #     System Prompt 管理（Caffeine 缓存）
│   │   ├── PromptLoaderService.java          #     XML 模板加载器
│   │   ├── ModelParamsService.java           #     模型参数管理（Caffeine 缓存）
│   │   └── UsageService.java                 #     用量统计
│   │
│   ├── controller/                           #   REST 接口
│   │   ├── ChatController.java               #     /api/chat, /api/models
│   │   ├── ConversationController.java       #     /api/conversations
│   │   ├── PromptController.java             #     /api/prompts
│   │   ├── ModelParamsController.java        #     /api/params
│   │   └── UsageController.java              #     /api/usage
│   │
│   ├── entity/                               #   数据实体
│   │   ├── SystemPrompt.java                 #     System Prompt 模板
│   │   ├── ModelParams.java                  #     模型运行时参数
│   │   └── TokenUsage.java                   #     Token 用量记录
│   │
│   ├── mapper/                               #   MyBatis-Plus Mapper
│   │   ├── SystemPromptMapper.java
│   │   ├── ModelParamsMapper.java
│   │   └── TokenUsageMapper.java
│   │
│   └── dto/                                  #   数据传输对象
│       ├── ChatRequest.java                  #     聊天请求
│       ├── ChatResponse.java                 #     聊天响应
│       ├── ConversationMessage.java          #     对话消息
│       ├── ConversationSummary.java          #     对话摘要
│       ├── ModelInfo.java                    #     模型信息
│       ├── ProviderModelInfo.java            #     按厂商分组的模型信息
│       ├── ModelsResponse.java               #     /models API 响应
│       ├── ModelParamsDTO.java               #     模型参数 DTO
│       ├── SystemPromptDTO.java              #     System Prompt DTO
│       ├── SystemPromptUpdateRequest.java    #     Prompt 更新请求
│       ├── PromptTemplate.java               #     Prompt 模板
│       ├── TokenUsageDTO.java                #     Token 用量 DTO
│       ├── UsageStats.java                   #     用量统计
│       └── ErrorResponse.java                #     错误响应
│
└── exception/                                # 异常处理
    ├── GlobalExceptionHandler.java           #   统一错误响应 (400/401/403/404/429/500)
    ├── BusinessException.java                #   业务异常
    ├── ContentFilteredException.java         #   内容过滤异常
    ├── ModelNotFoundException.java           #   模型不存在异常
    ├── ProviderNotFoundException.java        #   厂商不存在异常
    └── RateLimitExceededException.java       #   限流异常

resources/
└── mapper/                                   # MyBatis XML Mapper（SQL 与 Java 解耦）
    ├── SysUserMapper.xml
    ├── SysRoleMapper.xml
    ├── SysPermissionMapper.xml
    ├── SysUserRoleMapper.xml
    ├── SysRolePermissionMapper.xml
    └── TokenUsageMapper.xml
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
     │              │                                           │   │
     │     DeepSeekModelProvider   ZhipuModelProvider   MiniMaxModelProvider
     │              │                     │                    │      │
     │              ▼                     ▼                    ▼      │
     │      DeepSeekApi            ZhiPuAiApi           MiniMaxApi   │
     │              │                     │                    │      │
     │              ▼                     ▼                    ▼      │
     │         ChatClient            ChatClient           ChatClient  │
     │              │                     │                    │      │
     │              └──────────┬──────────┴──────────┬───────┘      │
     │                         ▼                      ▼              │
     │                   ChatClientRegistry ← ModelRegistryRefresher │
     │                                                               │
     ▼                                                               │
  ChatClient.call(prompt) ──→ 流式/阻塞响应                          │
```

**关键设计原则：**
- **策略模式**：`ModelProvider` 接口封装所有厂商差异（API 调用、ChatOptions 类型、模型列表获取）
- **服务定位模式**：`ProviderRegistry` 通过 Spring 构造器注入自动发现所有 Provider 实现
- **开闭原则 (OCP)**：新增厂商只需新增 Provider 实现类 + Properties record + RestClient Bean，零修改现有代码
- **容错启动**：未配置 API Key 的 Provider 静默跳过，不影响其他 Provider 和服务启动

### 2. 模型 ID 路由

```
请求 model="deepseek/deepseek-chat"  → Route(providerId="deepseek", modelId="deepseek-chat")
请求 model="zhipu/glm-4.7"          → Route(providerId="zhipu", modelId="glm-4.7")
请求 model="minimax/MiniMax-M2.1"    → Route(providerId="minimax", modelId="MiniMax-M2.1")
请求 model="deepseek-chat"           → Route(providerId="deepseek", modelId="deepseek-chat")  // 向后兼容，使用默认厂商
```

- `providerId/modelId` 复合格式精确路由到指定厂商
- 无前缀时回退到默认厂商（通过 `model.router.default-provider` 配置，默认 `deepseek`）

### 3. 双 Token 认证

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

### 4. 滑块验证码

```
GET /api/auth/captcha → {captchaId, backgroundImage, puzzleImage, answer(dev only)}
                                    ↓
前端拖动拼图块 → POST /api/auth/login 或 /register 携带 captchaId + captchaCode
                                    ↓
后端校验：Caffeine 缓存取答案，±5px 容差，一次性使用（用完即删）
```

- **纯 Java 2D 实现**，无外部图片/模型依赖
- 底图：随机渐变 + 噪点 + 干扰线
- 拼图块：Path2D 裁剪 + 半透明描边
- **dev 环境**返回 answer 坐标，方便 API 测试；其他环境不返回
- Caffeine 缓存 5 分钟 TTL，最大 10000 条

### 5. 自研雪花 ID（仅 SysUser）

借鉴百度 uid-generator 核心思想，自行实现：

```
┌─────────────────────────────────────────────────────────────────┐
│ 0 (1b) │ timestamp (41b) │ datacenterId (5b) │ workerId (5b) │ seq (12b) │
└─────────────────────────────────────────────────────────────────┘
```

| 特性 | 说明 |
|------|------|
| 自定义纪元 | 默认 2026-01-01，可用约 69 年 |
| datacenterId + workerId | 5+5=10 位，最多 1024 实例 |
| 时钟回拨容忍 | ≤5ms 等待恢复，超阈值报错 |
| 线程安全 | ReentrantLock |
| 吞吐量 | 每毫秒 4096 个，每秒 409.6 万 |
| 配置 | `app.snowflake.epoch/datacenter-id/worker-id`，支持环境变量 |

> 仅 `sys_user` 表使用雪花 ID（`IdType.INPUT`），其他表保持数据库自增。

### 6. RBAC 权限模型

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

### 7. Advisor 链

```
请求 → RateLimitAdvisor (order=0, 限流)
     → ContentFilterAdvisor (order=1, 输入检测)
     → ConversationContextAdvisor (动态 System Prompt + ModelParams)
     → 模型调用 (DeepSeek / 智谱 AI / MiniMax)
     → ContentFilterAdvisor (after, 输出过滤)
     → ChatMemoryAdvisor (对话记忆写入)
     → 响应
```

### 8. 数据库管理

- **sql/schema.sql** 全量建表脚本（首次部署手动执行），支持重复执行（IF NOT EXISTS + NOT EXISTS）
- **Spring AI initialize-schema 已关闭**，`spring_ai_chat_memory` 表由 schema.sql 建表
- **MyBatis-Plus** 全量 ORM，`@TableLogic` 逻辑删除
- **编程式事务** `TransactionTemplate`，不使用 `@Transactional`
- 详见 [数据库设计文档](docs/DATABASE.md)

### 9. 缓存策略

| 层级 | 技术 | TTL | 场景 |
|------|------|-----|------|
| 本地 | Caffeine | 30s | SystemPrompt / ModelParams 热路径 |
| 本地 | Caffeine | 5min | 滑块验证码答案 |
| 分布式 | Redis | 300s | 用户权限缓存 |
| 分布式 | Redis | 900s | Access Token 元数据 |
| 分布式 | Redis | 86400s | Refresh Token + 用户状态标记 |

## 环境配置

### Profile 说明

| Profile | 用途 | 密钥 | 验证码 answer |
|---------|------|------|--------------|
| `dev` | 本地开发 | 配置文件内含（禁止上传） | ✅ 返回 |
| `stable` | 测试/预发 | 环境变量注入 | ❌ 不返回 |
| `prod` | 生产（叠加 stable） | 环境变量注入 | ❌ 不返回 |

`prod` 是 `stable` 的补充覆盖层，启动时使用 `--spring.profiles.active=stable,prod`。

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

app:
  jwt:
    secret: ${JWT_SECRET}           # JWT 签名密钥（≥32 字符）
    access-expiration: 900          # Access Token 15 分钟
    refresh-expiration: 86400       # Refresh Token 24 小时
  snowflake:
    epoch: "2026-01-01T00:00:00+08:00"  # 雪花 ID 纪元
    datacenter-id: 0                # 数据中心 ID (0~31)
    worker-id: 0                    # 机器 ID (0~31)

model:
  router:
    default-provider: deepseek      # 默认厂商 ID（简单格式 modelId 回退到该厂商）
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
| `BusinessException` | 400 | 业务逻辑错误（统一替代 IllegalArgumentException） |
| `MethodArgumentNotValidException` | 400 | 参数校验失败 |
| 通用 `Exception` | 500 | 服务内部错误 |

## 设计原则

- **单一职责 (SRP)**：Controller 只做 HTTP 转发、Service 只做业务编排、Mapper 只做数据访问、CookieTokenManager 只管 Cookie 读写
- **依赖倒置 (DIP)**：Controller/Service 依赖接口（`AuthService`、`SysUserService`），不依赖 Impl 类；Advisor 依赖接口（`RateLimiter`、`ContentFilterService`）
- **开闭原则 (OCP)**：新增模型厂商只需实现 `ModelProvider` + 新增 `XxxProperties` + RestClient Bean；新增限流算法只需实现 `RateLimiter`；新增查询只需 Mapper 加方法 + XML 加 SQL
- **接口隔离 (ISP)**：每个 Service 提供独立的接口，Impl 类实现接口，Controller 按需注入
- **策略模式**：`ModelProvider` 封装厂商差异，ProviderRegistry 自动发现，零修改接入新厂商
- **编程式事务**：统一使用 `TransactionTemplate`，精确控制事务边界
- **安全纵深**：JWT 签名 + Redis 吊销 + 用户状态标记 + issuer 校验 + IP 限流 + 滑块验证码
- **自研核心**：雪花 ID 生成器、滑块验证码均为纯 Java 实现，无外部依赖
- **数据访问分层**：SQL 全部下沉到 Mapper 层（语义化方法 + XML），Service 层不含 `LambdaQueryWrapper`，仅保留分页查询使用 MyBatis-Plus 内置机制

## License

MIT
