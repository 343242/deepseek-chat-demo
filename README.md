# Chat Demo

基于 **Spring Boot 3.5 + Spring AI 1.1 + MyBatis-Plus 3.5** 的多厂商 AI 聊天助手后端。支持 **DeepSeek、智谱 AI (Zhipu)、MiniMax** 三家模型厂商，通过 Provider 抽象层实现统一路由。提供动态模型加载、SSE 流式响应、JDBC 对话记忆、RBAC 权限系统、滑块验证码、自研雪花 ID，并通过 Advisor 链实现限流与内容安全过滤。

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
| JJWT | 0.13.0 | JWT 双 Token（Access 15min + Refresh 24h） |
| Spring Data Redis | 随 Boot | Token 存储、权限缓存、IP 限流 |
| Caffeine | 3.x | 本地缓存（SystemPrompt / ModelParams / 验证码） |
| sensitive-word | 0.29.5 | DFA 敏感词过滤（纯内存，14W+ QPS） |
| PostgreSQL | 18 | 主数据库 |
| Redis | 8.2 | 缓存 / Token 存储 |

## 快速开始

### 1. 启动依赖服务

```bash
# 使用 docker compose 一键启动 PostgreSQL + Redis
cp .env.example .env   # 编辑 POSTGRES_PASSWORD 等配置
docker compose up -d

# 首次部署会自动执行 sql/schema.sql 建表
# 初始管理员：admin / admin123（生产环境请立即修改）
```

> PostgreSQL 18 变更了数据目录结构，docker-compose 已适配。如手动启动需注意 volume 挂载路径为 `/var/lib/postgresql`（而非旧版 `/var/lib/postgresql/data`）。

### 2. 配置环境变量

```bash
# 至少配置一个厂商的 API Key
export DEEPSEEK_API_KEY=sk-***
export ZHIPU_API_KEY=***         # 可选
export MINIMAX_API_KEY=***       # 可选

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
│   └── snowflake/                            #   自研雪花 ID 生成器
│       ├── SnowflakeProperties.java          #     配置：epoch / datacenterId / workerId
│       ├── SnowflakeIdGenerator.java         #     核心：64 位雪花算法（线程安全 + 时钟回拨容忍）
│       └── SnowflakeConfiguration.java       #     Spring Bean 注册
│
├── config/                                   # 基础配置
│   ├── ModelProviderAutoConfiguration.java   #   多厂商自动配置：Properties + RestClient + 模型初始化
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
│   ├── entity/                               #   SysUser(雪花ID), SysRole, SysPermission, ...
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
│   ├── dto/                                  #   Login/Register/Refresh/ChangePassword/...
│   └── controller/                           #   HTTP 转发
│       ├── AuthController.java               #     /api/auth/*
│       ├── UserController.java               #     /api/users/* (ADMIN)
│       └── RoleController.java               #     /api/roles/* (ADMIN)
│
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
│   ├── util/
│   │   └── ConversationIdUtil.java           #     对话 ID 用户隔离工具（构建/解析/前缀）
│   │
│   ├── service/                              #   业务服务
│   │   ├── ChatService.java                  #     聊天服务（阻塞 + 流式 + 记忆 + doFinally）
│   │   ├── ModelService.java                 #     模型管理（按厂商分组）
│   │   ├── ModelRegistryRefresher.java       #     模型注册刷新器
│   │   ├── ConversationService.java          #     对话管理（用户隔离）
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
│   │   ├── SystemPrompt.java
│   │   ├── ModelParams.java
│   │   └── TokenUsage.java
│   │
│   ├── mapper/                               #   MyBatis-Plus Mapper（语义化查询）
│   │   ├── SystemPromptMapper.java           #     selectByModelId / selectAllOrdered / deleteByModelId
│   │   ├── ModelParamsMapper.java            #     selectByModelId / selectAllOrdered / deleteByModelId
│   │   ├── TokenUsageMapper.java             #     selectByConversationId / aggregateByModel / ...
│   │   └── ConversationMapper.java           #     selectConversationsByPrefix / selectMessagesByConversationId
│   │
│   └── dto/                                  #   数据传输对象（全部 record）
│       ├── ChatRequest.java
│       ├── ChatResponse.java
│       ├── ConversationMessage.java
│       ├── ConversationSummary.java
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
└── exception/                                # 异常处理
    ├── GlobalExceptionHandler.java           #   统一错误响应 (400/401/403/404/429/500)
    ├── BusinessException.java                #   业务异常（统一替代 IllegalArgumentException）
    ├── ContentFilteredException.java         #   内容过滤异常
    ├── ModelNotFoundException.java           #   模型不存在异常
    ├── ProviderNotFoundException.java        #   厂商不存在异常
    └── RateLimitExceededException.java       #   限流异常

resources/
├── application.yml / application-{profile}.yml
├── static/prompt/                            # XML System Prompt 模板
│   ├── default.xml
│   ├── deepseek-chat.xml
│   └── deepseek-reasoner.xml
└── mapper/                                   # MyBatis XML Mapper
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
请求 → RateLimitAdvisor (order=0, 限流)
     → ContentFilterAdvisor (order=1, 输入检测)
     → ConversationContextAdvisor (动态 System Prompt + ModelParams)
     → 模型调用
     → ContentFilterAdvisor (after, 输出过滤)
     → ChatMemoryAdvisor (对话记忆写入)
     → 响应
```

### 9. 数据访问分层

```
Controller (@Valid 校验)
    ↓
Service (业务编排，不含 SQL)
    ↓
Mapper (语义化方法 + XML，所有查询逻辑在此)
    ↓
Database
```

- **Service 层不含 `LambdaQueryWrapper`**：所有查询通过 Mapper 语义化方法（`selectByModelId`、`selectAllOrdered`）或 XML SQL 实现
- **编程式事务** `TransactionTemplate`，不使用 `@Transactional`
- DTO 全部使用 Java record，Entity 不暴露给前端
- **sql/schema.sql** 全量建表脚本（IF NOT EXISTS 幂等），由 docker-compose 自动执行

### 10. 缓存策略

| 层级 | 技术 | TTL | 场景 |
|------|------|-----|------|
| 本地 | Caffeine | 30s | ModelParams 热路径 |
| 本地 | Caffeine | 5min | SystemPrompt / 验证码答案 |
| 分布式 | Redis | 300s | 用户权限缓存 |
| 分布式 | Redis | 900s | Access Token 元数据 |
| 分布式 | Redis | 86400s | Refresh Token + 用户状态标记 |

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

app:
  jwt:
    secret: ${JWT_SECRET}
    access-expiration: 900
    refresh-expiration: 86400
  snowflake:
    epoch: "2026-01-01T00:00:00+08:00"
    datacenter-id: 0
    worker-id: 0

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
| **依赖倒置 (DIP)** | 依赖接口（`AuthService`、`RateLimiter`、`ContentFilterService`），不依赖实现类 |
| **开闭原则 (OCP)** | 新增厂商 = 新增 Provider 类；新增限流算法 = 实现 `RateLimiter` 接口；零改旧代码 |
| **接口隔离 (ISP)** | 每个 Service 提供独立接口，Controller 按需注入 |
| **策略模式** | `ModelProvider` 封装厂商差异，`ProviderRegistry` 自动发现 |
| **DTO 隔离** | Entity 不暴露给前端，全部通过 record DTO 转换 |
| **数据访问下沉** | `LambdaQueryWrapper` 全部在 Mapper 层，Service 层不含 SQL 构建逻辑 |
| **编程式事务** | `TransactionTemplate` 精确控制事务边界 |
| **安全纵深** | JWT + Redis 吊销 + 用户状态 + IP 限流 + 滑块验证码 + Cookie SameSite |
| **自研核心** | 雪花 ID 生成器、滑块验证码均为纯 Java 实现，无外部依赖 |

## License

MIT
