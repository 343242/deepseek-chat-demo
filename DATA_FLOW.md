# DeepSeek Chat Demo — 完整数据链路

从用户注册到完成一次聊天的全链路数据流转。

---

## 一、应用启动阶段

```
Spring Boot 启动
  │
  ├─ DeepSeekAutoConfiguration
  │   ├─ 创建 RestClient Bean（base-url: api.deepseek.com，注入 API Key）
  │   └─ CommandLineRunner → ModelRegistryRefresher.refresh()
  │       │
  │       ├─ RestClient GET https://api.deepseek.com/models
  │       │   → ModelsResponse（模型列表：deepseek-chat, deepseek-reasoner 等）
  │       │
  │       ├─ 遍历模型列表，ChatClientFactory.create(modelId)
  │       │   ├─ DeepSeekApi.builder() → DeepSeekChatOptions → DeepSeekChatModel
  │       │   └─ ChatClient.builder(chatModel).build()
  │       │
  │       └─ ChatClientRegistry.replaceAll(clientsMap, modelList)
  │           → 原子替换，ConcurrentHashMap 存储 modelId → ChatClient
  │
  ├─ PromptLoaderService.@PostConstruct loadPrompts()
  │   ├─ 扫描 classpath:static/prompt/*.xml
  │   ├─ DOM 解析 XML（role / rules / constraints / capabilities）
  │   ├─ 保存 XML 原文 + 结构化字段到 volatile Map
  │   └─ 将 XML 原文写入 Redis（key=prompt:xml:{modelId}，TTL=1天）
  │
  └─ SecurityConfig.filterChain()
      ├─ 配置白名单路径：/api/auth/login, /api/auth/register, /api/auth/refresh
      ├─ 注册 JwtAuthenticationFilter
      └─ 其余所有路径要求认证
```

---

## 二、用户注册

```
POST /api/auth/register
Content-Type: application/json
{ "username": "alice", "password": "Pass1234", "nickname": "Alice" }
  │
  ├─ SecurityConfig：路径在白名单中，放行
  │
  ▼
AuthController.register(RegisterRequest)
  │
  ▼
AuthService.register(username, password, nickname)
  │
  ├─ 1. SysUserMapper.selectOne(username) → 检查用户名是否已存在
  │      SQL: SELECT * FROM sys_user WHERE username = ? AND deleted = 0
  │
  ├─ 2. PASSWORD_PATTERN 校验密码强度（≥8位，含字母+数字）
  │
  ├─ 3. BCryptPasswordEncoder.encode(password) → 加密密码
  │
  ├─ 4. TransactionTemplate.execute（编程式事务）
  │      ├─ new SysUser() → sysUserMapper.insert() → 写入 sys_user 表
  │      │   SQL: INSERT INTO sys_user (username, password, nickname, status) VALUES (...)
  │      │   → 自增主键回填 user.id（如 id=1）
  │      │
  │      └─ new SysUserRole(userId=1, roleId=2) → sysUserRoleMapper.insert()
  │          SQL: INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 2)
  │          → 分配默认 USER 角色
  │
  └─ 5. 返回 LoginResponse.UserInfo
         { id=1, username="alice", roles=["USER"], permissions=[] }
```

**涉及的表：**
| 表 | 操作 |
|---|---|
| `sys_user` | INSERT |
| `sys_user_role` | INSERT（绑定 roleId=2，即 USER 角色）|

---

## 三、用户登录

```
POST /api/auth/login
Content-Type: application/json
{ "username": "alice", "password": "Pass1234" }
  │
  ├─ SecurityConfig：路径在白名单中，放行
  │
  ▼
AuthController.login(LoginRequest, HttpServletRequest)
  │
  ▼
AuthService.login(username, password, ip)
  │
  ├─ 1. TokenCacheService.isLoginRateLimited(ip)
  │      Redis: GET ratelimit:login:{ip} → 检查是否 > 10 次
  │
  ├─ 2. TokenCacheService.incrementLoginAttempts(ip)
  │      Redis: INCR ratelimit:login:{ip}（TTL 300s）
  │
  ├─ 3. SysUserMapper.selectOne(username) → 查用户
  │      SQL: SELECT * FROM sys_user WHERE username = ? AND deleted = 0
  │
  ├─ 4. 检查 user.status == 1（数据库状态）
  │
  ├─ 5. TokenCacheService.getUserStatus(userId)
  │      Redis: GET auth:status:{userId} → 检查是否 disabled/deleted
  │
  ├─ 6. BCryptPasswordEncoder.matches(password, user.password) → 校验密码
  │
  ├─ 7. SysUserRoleMapper.selectRoleIdsByUserId(1) → [2]
  │      SysRoleMapper.selectBatchIds([2]) → 角色名列表 ["USER"]
  │
  ├─ 8. JwtTokenProvider.generateAccessToken(userId=1, roles=["USER"])
  │      → JWT payload: { sub: "1", jti: uuid, roles: ["USER"], type: "access", exp: now+15min }
  │      → HMAC-SHA256 签名
  │
  ├─ 9. JwtTokenProvider.generateRefreshToken(userId=1)
  │      → JWT payload: { sub: "1", jti: uuid, type: "refresh", exp: now+24h }
  │
  ├─ 10. TokenCacheService.storeAccessToken(userId, tokenId, roles)
  │       Redis: SET auth:token:1:{jti} → { roles, createdAt }（TTL 900s）
  │
  ├─ 11. TokenCacheService.storeRefreshToken(refreshToken, userId)
  │       Redis: SET auth:refresh:{sha256(token)} → userId（TTL 86400s）
  │       Redis: SADD auth:user_refresh:1 → sha256(token)
  │
  ├─ 12. AuthService.loadUserPermissions(userId=1)
  │       SysUserRoleMapper → roleIds [2]
  │       SysRolePermissionMapper.selectPermissionsByRoleId(2) → 权限列表
  │       如: ["chat:send", "conversation:manage", "model:view", ...]
  │       TokenCacheService.cacheUserPermissions(1, permissions)
  │       Redis: SET auth:perms:1 → permissions JSON（TTL 300s）
  │
  └─ 13. 返回 LoginResponse
          {
            accessToken: "eyJhbG...",
            refreshToken: "eyJhbG...",
            tokenType: "Bearer",
            expiresIn: 900,
            userInfo: { id=1, username="alice", roles=["USER"],
                        permissions=["chat:send","conversation:manage",...] }
          }
```

**涉及存储：**
| 存储 | 操作 |
|---|---|
| PostgreSQL `sys_user` | SELECT |
| PostgreSQL `sys_user_role` | SELECT |
| PostgreSQL `sys_role` | SELECT |
| PostgreSQL `sys_role_permission` | SELECT |
| PostgreSQL `sys_permission` | SELECT（JOIN） |
| Redis | 多次读写（限流、Token 存储、权限缓存） |

---

## 四、后续请求认证（每次请求）

```
客户端携带 Token 请求任意 API
Authorization: Bearer eyJhbG...
  │
  ▼
JwtAuthenticationFilter.doFilterInternal()
  │
  ├─ 1. SecurityUtils.extractToken(request) → 从 Header 提取 Bearer Token
  │
  ├─ 2. JwtTokenProvider.validateToken(token) → 验证签名 + 过期时间
  │
  ├─ 3. JwtTokenProvider.getTokenType(token) → 确认是 "access" 类型
  │
  ├─ 4. JwtTokenProvider.getUserIdFromToken(token) → userId = 1
  │
  ├─ 5. JwtTokenProvider.getJtiFromToken(token) → tokenId (jti)
  │      TokenCacheService.isAccessTokenValid(userId=1, jti)
  │      Redis: EXISTS auth:token:1:{jti} → 确认未被吊销
  │
  ├─ 6. TokenCacheService.getUserStatus(userId=1)
  │      Redis: GET auth:status:1 → 确认未被禁用
  │
  ├─ 7. TokenCacheService.getUserPermissions(userId=1) → Redis 缓存命中？
  │      ├─ 命中 → 直接使用
  │      └─ 未命中 → AuthService.loadUserPermissions(userId) → 回查数据库
  │
  ├─ 8. 构建 SecurityContext：
  │      authorities = permissions.map(p → new SimpleGrantedAuthority(p))
  │                  + roles.map(r → new SimpleGrantedAuthority("ROLE_" + r))
  │      principal = userId (Long)
  │      → SecurityContextHolder.setAuthentication(...)
  │
  └─ 9. filterChain.doFilter() → 继续后续处理
         （此时 SecurityContext 中已有 userId + 权限列表）
```

---

## 五、发起一次聊天

```
POST /api/chat
Authorization: Bearer eyJhbG...
Content-Type: application/json
{ "model": "deepseek-chat", "message": "你好", "conversationId": "conv-001" }
  │
  ├─ JwtAuthenticationFilter → 认证通过，userId=1, authorities=[chat:send, ...]
  │
  ├─ @PreAuthorize("hasAuthority('chat:send')") → 权限校验通过
  │
  ▼
ChatController.chat(ChatRequest)
  │  record ChatRequest(model="deepseek-chat", message="你好", conversationId="conv-001")
  │
  ├─ validateChatRequest() → 校验 model 和 message 非空
  │
  ▼
ChatService.chat(ChatRequest)
  │
  ├─ 1. SecurityUtils.getCurrentUserId() → 1
  │      从 SecurityContextHolder 获取
  │
  ├─ 2. buildIsolatedConversationId(userId=1, "conv-001")
  │      → "u_1_conv-001"
  │      （用户隔离：不同用户即使 conversationId 相同，底层数据也不冲突）
  │
  ├─ 3. ChatClientRegistry.get("deepseek-chat") → ChatClient 实例
  │      从启动时注册的 Map 中获取
  │
  ├─ 4. buildRequestSpec(chatClient, "deepseek-chat", "你好", "u_1_conv-001")
  │      │
  │      ├─ 4a. 构建请求：chatClient.prompt().user("你好")
  │      │
  │      ├─ 4b. 注入 System Prompt（XML 原文格式）：
  │      │   SystemPromptService.getPrompt("deepseek-chat")
  │      │   ├─ 1) XML 模板（内存，最高优先级）
  │      │   │   → PromptLoaderService.getPrompt() → volatile Map
  │      │   ├─ 2) Caffeine 本地缓存（TTL 5 分钟，热路径加速）
  │      │   ├─ 3) Redis 缓存（key=prompt:xml:{modelId}，TTL 1 天）
  │      │   ├─ 4) PostgreSQL system_prompt 表（兜底）
  │      │   └─ spec.system(rawXmlPrompt) → XML 原文直接发给 DeepSeek
  │      │
  │      ├─ 4c. 注入动态参数（如有）：
  │      │   ModelParamsService.getParams("deepseek-chat")
  │      │   → DeepSeekChatOptions(temperature, maxTokens, topP, ...)
  │      │
  │      └─ 4d. 构建 Advisor 链：
  │          [0] ConversationContextAdvisor(order=-1)
  │              → 将 conversationId="u_1_conv-001" 注入 Advisor context
  │          [1] RateLimitAdvisor(order=0)
  │              → 令牌桶限流检查（纯内存，基于 conversationId）
  │          [2] ContentFilterAdvisor(order=1)
  │              → 调用 SensitiveWordFilterService 检测用户输入敏感词
  │          [3] MessageChatMemoryAdvisor(order=默认)
  │              → ChatMemory 加载历史消息（基于 conversationId）
  │              → 查询 spring_ai_chat_memory 表
  │              SQL: SELECT * FROM spring_ai_chat_memory
  │                   WHERE conversation_id = 'u_1_conv-001' ORDER BY created_at
  │
  ├─ 5. requestSpec.call().chatResponse()
  │      │ Spring AI 框架内部流程：
  │      │
  │      ├─ Advisor 链 before() 依次执行：
  │      │   ConversationContextAdvisor → 注入 context
  │      │   RateLimitAdvisor → 检查限流
  │      │   ContentFilterAdvisor → 敏感词检测（输入侧）
  │      │   MessageChatMemoryAdvisor → 从 DB 加载历史消息，拼入 prompt
  │      │
  │      ├─ 构建最终请求发送到 DeepSeek API：
  │      │   POST https://api.deepseek.com/chat/completions
  │      │   {
  │      │     "model": "deepseek-chat",
  │      │     "messages": [
  │      │       { "role": "system", "content": "## 角色\n你是..." },
  │      │       { "role": "user", "content": "历史消息1" },  // 来自 ChatMemory
  │      │       { "role": "assistant", "content": "历史回复" },
  │      │       { "role": "user", "content": "你好" }        // 当前消息
  │      │     ],
  │      │     "temperature": 0.7,
  │      │     "stream": false
  │      │   }
  │      │
  │      ├─ DeepSeek 返回响应：
  │      │   { "choices": [{ "message": { "role": "assistant", "content": "你好！..." } }],
  │      │     "usage": { "prompt_tokens": 85, "completion_tokens": 42, "total_tokens": 127 } }
  │      │
  │      └─ Advisor 链 after() 逆序执行：
  │          MessageChatMemoryAdvisor → 将本轮 Q&A 写入 ChatMemory
  │             SQL: INSERT INTO spring_ai_chat_memory (conversation_id, role, content, created_at)
  │                  VALUES ('u_1_conv-001', 'user', '你好', now())
  │             SQL: INSERT INTO spring_ai_chat_memory (conversation_id, role, content, created_at)
  │                  VALUES ('u_1_conv-001', 'assistant', '你好！...', now())
  │          ContentFilterAdvisor → 过滤输出中的敏感词（替换为 ***）
  │          RateLimitAdvisor → 无操作
  │          ConversationContextAdvisor → 无操作
  │
  ├─ 6. recordUsage("u_1_conv-001", "deepseek-chat", aiResponse, duration)
  │      UsageService.recordUsage()
  │      SQL: INSERT INTO token_usage
  │           (conversation_id, model_id, prompt_tokens, completion_tokens, total_tokens, duration_ms)
  │           VALUES ('u_1_conv-001', 'deepseek-chat', 85, 42, 127, 1520)
  │
  └─ 7. 返回 ChatResponse
         { model="deepseek-chat", content="你好！...", conversationId="conv-001" }
         （注意：返回给前端的是原始 conversationId，不暴露内部前缀）
```

---

## 六、流式聊天（SSE）

```
POST /api/chat/stream
{ "model": "deepseek-chat", "message": "写一首诗", "conversationId": "conv-001" }
  │
  ▼ 与阻塞式几乎相同的链路，区别在于：
  │
  ├─ ChatService.chatStream() 返回 Flux<String>
  │
  ├─ requestSpec.stream().content()
  │   → DeepSeek API 请求 "stream": true
  │   → 逐 chunk 返回 SSE 数据：
  │      data: {"choices":[{"delta":{"content":"春"}}]}
  │      data: {"choices":[{"delta":{"content":"风"}}]}
  │      data: {"choices":[{"delta":{"content":"拂"}}]}
  │      ...
  │      data: [DONE]
  │
  └─ doOnComplete() → 记录用量（流式模式下 token 数记为 -1）
```

---

## 七、查看对话历史

```
GET /api/conversations?page=1&size=20
Authorization: Bearer eyJhbG...
  │
  ├─ JwtAuthenticationFilter → userId=1
  ├─ @PreAuthorize("hasAuthority('conversation:manage')") → 通过
  │
  ▼
ConversationController.listConversations(page=1, size=20)
  │
  ▼
ConversationService.listConversations(1, 20)
  │
  ├─ SecurityUtils.getCurrentUserId() → 1
  ├─ prefix = "u_1_%"
  │
  └─ JdbcTemplate.query(
        "SELECT conversation_id, COUNT(*) AS msg_count, ... " +
        "FROM spring_ai_chat_memory " +
        "WHERE conversation_id LIKE 'u_1_%' " +    ← 用户隔离过滤
        "GROUP BY conversation_id ORDER BY last_msg DESC LIMIT 20 OFFSET 0")
     │
     └─ stripUserPrefix("u_1_conv-001") → "conv-001"
        返回 [ ConversationSummary("conv-001", msgCount=4, firstMsg=..., lastMsg=...) ]
        （前端看到的是干净的 conversationId，无用户前缀）
```

---

## 数据流全景图

```
┌─────────┐    POST /register     ┌──────────────┐    INSERT     ┌───────────┐
│  Client  │ ──────────────────→  │ AuthController│ ──────────→  │ PostgreSQL│
│         │                       │  + AuthService│               │  sys_user  │
│         │    POST /login        │               │    SELECT     │  sys_role  │
│         │ ──────────────────→   │               │ ←────────── │  sys_user  │
│         │                       │               │               │   _role    │
│         │   ← JWT Token pair    │               │    WRITE      │  sys_role  │
│         │                       │               │ ──────────→  │  _permission│
└─────────┘                       └──────────────┘               └───────────┘
     │                                    │
     │  Bearer Token                      │ permissions/roles
     │                                    ▼
     │                            ┌──────────────┐
     │                            │    Redis      │
     │                            │  auth:token:  │
     │                            │  auth:refresh:│
     │                            │  auth:perms:  │
     │                            │  auth:status: │
     │                            │  ratelimit:   │
     │                            └──────────────┘
     │
     │  POST /api/chat
     │  { model, message, conversationId }
     ▼
┌──────────────┐   userId from SecurityContext
│JwtAuthFilter │──────────────────────────────────────────┐
└──────────────┘                                          │
                                                          ▼
┌──────────────┐   get ChatClient    ┌───────────────┐
│ChatController│ ─────────────────→  │ChatClient      │
│              │                     │ Registry       │ ← 启动时从 DeepSeek API
│              │   chat(request)     │ (deepseek-chat)│    拉取模型列表并注册
│              │ ─────────────────→  │ (deepseek-     │
└──────────────┘                     │  reasoner)     │
        │                            └───────────────┘
        ▼                                    │
┌──────────────┐                             │
│ ChatService  │                             │
│              │                             │
│ 1. userId=1  │    ┌─────────────────────┐  │
│ 2. convId    │    │  Advisor Chain       │  │
│    =u_1_xxx  │    │                      │  │
│              │    │ ConversationContext   │  │
│              │───→│ RateLimit (令牌桶)    │  │
│              │    │ ContentFilter (敏感词)│  │
│              │    │ ChatMemory (历史消息) │  │
│              │    └─────────────────────┘  │
│              │              │               │
│              │              ▼               ▼
│              │    ┌─────────────────────────┐
│              │    │  DeepSeek API            │
│              │    │  POST /chat/completions  │
│              │    └─────────────────────────┘
│              │              │
│              │              ▼ response
│              │    ┌─────────────────────┐
│              │    │  Advisor after()     │
│              │    │  ChatMemory 写入 DB  │────→ PostgreSQL
│              │    │  ContentFilter 输出  │      spring_ai_chat_memory
│              │    │  Usage 记录         │────→ PostgreSQL
│              │    └─────────────────────┘      token_usage
│              │
│              │    SystemPromptService.getPrompt()
│              │      ├─ 1) XML 模板（内存，最高）
│              │      ├─ 2) Caffeine 缓存（5min TTL）
│              │      ├─ 3) Redis prompt:xml:{modelId}
│              │      └─ 4) PostgreSQL system_prompt 表（兜底）
│              │    → XML 原文直接作为 system role 发给 DeepSeek
└──────────────┘
        │
        ▼
┌─────────┐
│  Client  │  ← ChatResponse { model, content, conversationId }
└─────────┘
```

---

## 核心设计要点

### 用户隔离机制
- **conversationId 拼接规则：** `u_{userId}_{rawConversationId}`
- **写入时：** ChatService 从 SecurityContext 取 userId，自动拼接前缀
- **读取时：** ConversationService 查询加 `LIKE 'u_{userId}_%'` 过滤
- **返回时：** 自动剥离前缀，前端无感知
- **效果：** 用户 A 的 "conv-001"（u_1_conv-001）和用户 B 的 "conv-001"（u_2_conv-001）完全隔离

### 配置文件分级
- **application.yml** — 仅含 profile 路由和通用配置（无密钥）
- **application-dev.yml** — 开发环境，含真实 API Key，已加入 .gitignore 禁止上传
- **application-stable.yml** — 生产模板，仅含 `${ENV_VAR}` 占位符，无默认值

### System Prompt 优先级
1. **XML 模板文件（内存）** — `classpath:static/prompt/*.xml`，启动时加载，最高优先级
2. **Caffeine 本地缓存** — TTL 5 分钟，热路径加速，减少 Redis/PG 访问
3. **Redis 缓存** — XML 模板启动时同步写入，key=`prompt:xml:{modelId}`，TTL 1 天
4. **PostgreSQL `system_prompt` 表** — 通过 API 动态修改，作为兜底
5. 无配置则不注入 system prompt

### Advisor 执行顺序
| Order | Advisor | 职责 |
|-------|---------|------|
| -1 | ConversationContextAdvisor | 注入 conversationId 到 context |
| 0 | RateLimitAdvisor | 令牌桶限流 |
| 1 | ContentFilterAdvisor | 敏感词检测（输入拦截 + 输出替换）|
| 默认 | MessageChatMemoryAdvisor | 历史消息加载与保存 |
