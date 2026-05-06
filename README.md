# DeepSeek Chat Demo

基于 **Spring Boot 3 + Spring AI 1.x + MyBatis-Plus** 的 DeepSeek 聊天助手后端项目。支持动态模型加载、SSE 流式响应、JDBC 对话记忆、RBAC 用户权限系统，并通过自定义 Advisor 链实现限流与内容安全过滤。

## 技术栈

| 依赖 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 运行时 |
| Spring Boot | 3.5.14 | 应用框架 |
| Spring AI | 1.1.5 | AI 模型集成 |
| spring-ai-starter-model-deepseek | 1.1.5 | DeepSeek 官方 SDK |
| MyBatis-Plus | 3.5.16 | ORM 框架（全量替代 JPA） |
| Spring Security | 随 Boot | 认证与授权 |
| JJWT | 0.12.6 | JWT 双 Token（Access 15min + Refresh 24h） |
| Spring Data Redis | 随 Boot | Token 存储、权限缓存、IP 限流 |
| Flyway | 随 Boot | 数据库版本迁移 |
| Caffeine | 3.1.x | 本地缓存（SystemPrompt / ModelParams 热路径） |
| sensitive-word | 0.29.5 | DFA 敏感词过滤（纯内存，14W+ QPS） |
| PostgreSQL | 18 | 主数据库 |
| Redis | 7 | 缓存 / Token 存储 |

## 快速开始

### 1. 启动依赖服务

```bash
# PostgreSQL
docker run -d --name deepseek-chat-pg \
  --restart unless-stopped \
  -e POSTGRES_DB=deepseek_chat \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:18-bookworm

# Redis
docker run -d --name deepseek-chat-redis \
  --restart unless-stopped \
  -p 6379:6379 \
  redis:7-bookworm redis-server --appendonly yes
```

### 2. 配置环境变量

```bash
export DEEPSEEK_API_KEY=sk-your-actual-api-key
export JWT_SECRET=your-very-long-secret-at-least-32-chars-long!!
# 可选
export POSTGRES_PASSWORD=your-db-password
export REDIS_PASSWORD=your-redis-password
```

### 3. 编译运行

```bash
mvn clean package -DskipTests
java -jar target/deepseek-chat-demo-0.0.1-SNAPSHOT.jar
```

> Flyway 会在首次启动时自动创建所有表和初始数据。
> 初始管理员账号：`admin` / `admin123`（生产环境请立即修改）

### 4. 验证

```bash
# 登录获取 Token
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 查看可用模型
curl http://localhost:8080/api/models \
  -H "Authorization: Bearer <accessToken>"

# 聊天
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <accessToken>" \
  -d '{"model":"deepseek-chat","message":"你好","conversationId":"test"}'

# SSE 流式聊天
curl http://localhost:8080/api/chat/stream?model=deepseek-chat&message=你好&conversationId=test \
  -H "Authorization: Bearer <accessToken>"
```

## API 接口

### 认证

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| `POST` | `/api/auth/register` | 公开 | 用户注册 |
| `POST` | `/api/auth/login` | 公开 | 登录，返回双 Token |
| `POST` | `/api/auth/refresh` | 公开 | 刷新 Access Token |
| `POST` | `/api/auth/logout` | 登录 | 登出（吊销 Token） |
| `GET` | `/api/auth/me` | 登录 | 获取当前用户信息 + 权限 |
| `PATCH` | `/api/auth/me/password` | 登录 | 修改密码 |
| `PATCH` | `/api/auth/me/profile` | 登录 | 修改个人信息 |

### 聊天

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| `GET` | `/api/models` | `chat:send` | 获取可用模型列表 |
| `POST` | `/api/chat` | `chat:send` | 阻塞式聊天 |
| `GET` | `/api/chat/stream` | `chat:stream` | SSE 流式聊天（query params） |
| `POST` | `/api/chat/stream` | `chat:stream` | SSE 流式聊天（JSON body） |
| `POST` | `/api/models/refresh` | `model:config` | 刷新模型列表 |

### 对话管理

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| `GET` | `/api/conversations` | `conversation:manage` | 对话列表（分页） |
| `GET` | `/api/conversations/{id}` | `conversation:manage` | 对话消息明细 |
| `DELETE` | `/api/conversations/{id}` | `conversation:manage` | 清空对话 |
| `GET` | `/api/conversations/{id}/export` | `conversation:manage` | 导出对话 |

### 系统配置（管理员）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| `GET/PUT/DELETE` | `/api/prompts/{modelId}` | `prompt:manage` | System Prompt 管理 |
| `GET/PUT/DELETE` | `/api/params/{modelId}` | `model:config` | 模型参数管理 |
| `GET` | `/api/usage/records` | `usage:view` | 用量记录 |

### 用户管理（管理员）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| `GET` | `/api/users` | `user:manage` | 用户列表（分页） |
| `GET` | `/api/users/{id}` | `user:manage` | 用户详情 |
| `PATCH` | `/api/users/{id}` | `user:manage` | 修改用户信息 |
| `PATCH` | `/api/users/{id}/status` | `user:manage` | 启用/禁用 |
| `PATCH` | `/api/users/{id}/roles` | `user:manage` | 分配角色 |
| `DELETE` | `/api/users/{id}` | `user:manage` | 删除用户 |

### 角色权限（管理员）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| `GET/POST/PUT/DELETE` | `/api/roles` | `role:manage` | 角色 CRUD |
| `GET/PATCH` | `/api/roles/{id}/permissions` | `role:manage` | 权限分配 |
| `GET` | `/api/roles/permissions` | `role:manage` | 全部权限列表 |

## 项目结构

```
src/main/java/com/demo/deepseekchat/
├── DeepseekChatApplication.java          # @MapperScan 启动类
│
├── config/                               # 基础配置
│   ├── DeepSeekProperties.java           #   DeepSeek API 配置属性
│   ├── DeepSeekAutoConfiguration.java    #   自动配置：模型列表拉取 + ChatClient 注册
│   ├── AdvisorAutoConfiguration.java     #   Advisor 编排
│   ├── MyBatisPlusConfig.java            #   分页插件
│   ├── RedisConfig.java                  #   Redis 序列化配置
│   └── TransactionConfig.java            #   TransactionTemplate Bean
│
├── security/                             # 安全模块
│   ├── config/
│   │   ├── SecurityConfig.java           #   SecurityFilterChain + CORS + @EnableMethodSecurity
│   │   └── JwtProperties.java            #   JWT 配置属性
│   ├── filter/
│   │   └── JwtAuthenticationFilter.java  #   JWT 认证过滤器
│   ├── service/
│   │   └── TokenCacheService.java        #   Redis token 存储/吊销/权限缓存/限流
│   └── util/
│       ├── JwtTokenProvider.java         #   JWT 生成/验证 (jti claim, issuer 校验)
│       └── SecurityUtils.java            #   getCurrentUserId / extractToken 工具类
│
├── user/                                 # RBAC 用户模块
│   ├── entity/                           #   SysUser, SysRole, SysPermission, SysUserRole, SysRolePermission
│   ├── mapper/                           #   MyBatis-Plus BaseMapper + 自定义查询
│   ├── service/
│   │   ├── AuthService.java              #   认证：登录/注册/刷新/登出/改密
│   │   ├── SysUserService.java           #   用户 CRUD（含分页、状态切换、角色分配）
│   │   ├── SysRoleService.java           #   角色管理 + 权限分配 + 缓存失效
│   │   └── SysPermissionService.java     #   权限 CRUD
│   ├── dto/                              #   Login/Register/Refresh/ChangePassword/UserUpdate/AssignRoles
│   └── controller/
│       ├── AuthController.java           #   /api/auth/*
│       ├── UserController.java           #   /api/users/* (ADMIN)
│       └── RoleController.java           #   /api/roles/* (ADMIN)
│
├── chat/                                 # ChatClient 管理层
│   ├── ChatClientFactory.java            #   ChatClient 构建工厂
│   └── ChatClientRegistry.java           #   ChatClient 注册中心
│
├── advisor/                              # Spring AI Advisor 链
│   ├── RateLimiter.java                  #   限流器接口
│   ├── TokenBucketLimiter.java           #   令牌桶实现
│   ├── RateLimitAdvisor.java             #   限流 Advisor (order=0)
│   ├── ContentFilterAdvisor.java         #   内容安全 Advisor (order=1)
│   └── ConversationContextAdvisor.java   #   对话上下文注入 Advisor
│
├── content/                              # 内容安全
│   ├── ContentFilterService.java         #   过滤服务接口
│   └── SensitiveWordFilterService.java   #   sensitive-word DFA 实现
│
├── service/                              # 业务服务
│   ├── ChatService.java                  #   聊天服务（阻塞 + 流式 + 记忆 + 动态参数）
│   ├── ModelService.java                 #   模型管理
│   ├── ModelRegistryRefresher.java       #   模型列表热刷新
│   ├── ConversationService.java          #   对话管理（JdbcTemplate）
│   ├── SystemPromptService.java          #   System Prompt 管理（Caffeine 缓存）
│   ├── ModelParamsService.java           #   模型参数管理（Caffeine 缓存）
│   └── UsageService.java                 #   用量统计
│
├── controller/                           # REST 接口
│   ├── ChatController.java               #   /api/chat, /api/models
│   ├── ConversationController.java       #   /api/conversations
│   ├── PromptController.java             #   /api/prompts
│   ├── ModelParamsController.java        #   /api/params
│   └── UsageController.java              #   /api/usage
│
├── model/                                # 聊天业务模型
│   ├── entity/                           #   SystemPrompt, ModelParams, TokenUsage
│   ├── mapper/                           #   MyBatis-Plus Mapper
│   └── dto/                              #   ChatRequest, ChatResponse, ModelInfo, ...
│
└── exception/                            # 异常处理
    ├── GlobalExceptionHandler.java       #   统一错误响应 (400/401/403/404/429/500)
    └── ...
```

## 架构设计

### 1. 双 Token 认证

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

### 2. RBAC 权限模型

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

### 3. Advisor 链

```
请求 → RateLimitAdvisor (order=0, 限流)
     → ContentFilterAdvisor (order=1, 输入检测)
     → ConversationContextAdvisor (动态 System Prompt + ModelParams)
     → DeepSeek 模型调用
     → ContentFilterAdvisor (after, 输出过滤)
     → ChatMemoryAdvisor (对话记忆写入)
     → 响应
```

### 4. 数据库管理

- **Flyway** 统一版本迁移：`V1__init_user_module.sql` + `V2__migrate_jpa_to_mp.sql`
- **MyBatis-Plus** 全量 ORM，`@TableLogic` 逻辑删除
- **编程式事务** `TransactionTemplate`，不使用 `@Transactional`
- **Spring AI JDBC ChatMemory** 独立管理 `spring_ai_chat_memory` 表

### 5. 缓存策略

| 层级 | 技术 | TTL | 场景 |
|------|------|-----|------|
| 本地 | Caffeine | 30s | SystemPrompt / ModelParams 热路径 |
| 分布式 | Redis | 300s | 用户权限缓存 |
| 分布式 | Redis | 900s | Access Token 元数据 |
| 分布式 | Redis | 86400s | Refresh Token + 用户状态标记 |

## 代码图谱

使用 [graphify](https://github.com/nichochar/graphify) 生成：

```
cd deepseek-chat-demo && graphify update .
```

生成结果：**424 节点 · 530 边 · 28 社区**

核心枢纽节点（连接数最多）：
1. `SysUser` (23 edges) — 用户实体
2. `ModelParams` (21 edges) — 模型参数
3. `SysPermission` (21 edges) — 权限实体
4. `SysRole` (15 edges) — 角色实体
5. `SystemPrompt` (13 edges) — 系统提示词

生成的文件位于 `graphify-out/`：
- `graph.html` — 交互式可视化图谱（浏览器打开）
- `graph.json` — 图数据（JSON 格式）
- `GRAPH_REPORT.md` — 自动生成的架构分析报告

## 异常处理

| 异常 | HTTP 状态码 | 场景 |
|------|------------|------|
| `RateLimitExceededException` | 429 | 登录限流 / API 限流 |
| `AuthenticationException` | 401 | 未认证 / Token 失效 |
| `AccessDeniedException` | 403 | 权限不足 |
| `ContentFilteredException` | 400 | 敏感词 |
| `ModelNotFoundException` | 404 | 模型不存在 |
| `MethodArgumentNotValidException` | 400 | 参数校验失败 |
| `IllegalArgumentException` | 400 | 业务参数错误 |
| 通用 `Exception` | 500 | 服务内部错误 |

## 设计原则

- **单一职责**：每个类只做一件事
- **依赖倒置**：Advisor 依赖接口（`RateLimiter`、`ContentFilterService`）
- **开闭原则**：新增限流算法只需实现 `RateLimiter`，新增角色只需 INSERT + 授权
- **编程式事务**：统一使用 `TransactionTemplate`，精确控制事务边界
- **安全纵深**：JWT 签名 + Redis 吊销 + 用户状态标记 + issuer 校验 + IP 限流

## License

MIT
