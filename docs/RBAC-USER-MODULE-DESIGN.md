# RBAC 用户模块设计文档 v2

> 版本: 2.0 | 日期: 2026-05-07 | ORM: MyBatis-Plus 3.5.16 | 缓存: Redis 7

---

## 1. 模块概述

为 deepseek-chat-demo 引入基于 RBAC（Role-Based Access Control）的用户权限系统。

**当前阶段**：管理员 + 普通用户两种角色，满足基本权限隔离需求。
**扩展性保证**：数据模型和代码结构支持未来新增角色、权限、菜单等，无需重构。

### 1.1 设计原则

- **全量 MyBatis-Plus**：移除 Spring Data JPA，所有表（含原 JPA 管理的 system_prompt / model_params / token_usage）统一使用 MyBatis-Plus。Spring AI JDBC ChatMemory 使用 JdbcTemplate 独立管理，不受影响
- **认证采用 JWT + Redis**：15 分钟 access token + 24 小时 refresh token，Redis 存储 token 元数据实现吊销和实时权限变更
- **密码存储使用 BCrypt**，不可逆加密
- **编程式事务**控制（`TransactionTemplate`），不使用 `@Transactional` 注解
- **Flyway 管理 schema 迁移**，替代 `ddl-auto: update`
- **接口统一前缀** `/api/auth` 和 `/api/users`

### 1.2 v1 → v2 变更摘要

| 变更项 | v1 | v2 |
|--------|----|----|
| ORM | JPA + MyBatis-Plus 共存 | 全量 MyBatis-Plus（移除 JPA） |
| Token 策略 | 单一 JWT 24h | Access 15min + Refresh 24h |
| Token 存储 | 无状态 | Redis（支持吊销 + 权限实时生效） |
| JWT 载荷 | userId + roles + permissions | userId + roles（权限从 Redis/DB 查询） |
| Schema 管理 | JPA ddl-auto + 手写 SQL | Flyway 统一迁移 |
| 权限校验 | 自定义 @RequirePermission + Interceptor | Spring Security @PreAuthorize + GrantedAuthority |
| ADMIN 放行 | 硬编码角色判断 | 通配权限 `*:*` |
| 数据库 DDL | MySQL 风格 | PostgreSQL 原生语法 |

---

## 2. 基础设施

### 2.1 Docker 容器

#### PostgreSQL 18 (bookworm)

```bash
docker run -d --name deepseek-chat-pg \
  --restart unless-stopped \
  -e POSTGRES_DB=deepseek_chat \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -v pgdata:/var/lib/postgresql/data \
  -p 5432:5432 \
  postgres:18-bookworm
```

#### Redis 7 (bookworm)

```bash
docker run -d --name deepseek-chat-redis \
  --restart unless-stopped \
  -p 6379:6379 \
  -v redisdata:/data \
  redis:7-bookworm redis-server --appendonly yes
```

### 2.2 依赖变更

#### pom.xml 新增

```xml
<!-- MyBatis-Plus 3.5.16 (Spring Boot 3) -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.16</version>
</dependency>

<!-- Spring Data Redis -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- Spring Security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- JWT (JJWT 0.12.6) -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>

<!-- Flyway -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

#### pom.xml 移除

```xml
<!-- 移除 Spring Data JPA -->
<!-- <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency> -->

<!-- 移除 Lombok（项目未使用） -->
<!-- <dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency> -->
```

### 2.3 配置变更

#### application.yml

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/deepseek_chat
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10

  # Flyway — 替代 JPA ddl-auto
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
    validate-on-migrate: true

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 3000ms

  ai:
    deepseek:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY:your-api-key-here}
      chat:
        model: deepseek-chat
        temperature: 0.7
    chat:
      memory:
        repository:
          jdbc:
            initialize-schema: always

# JWT
app:
  jwt:
    secret: ${JWT_SECRET:myDefaultSecretKeyForDevOnlyMustBe32CharsLong!!}
    access-expiration: 900           # 15 分钟 (秒)
    refresh-expiration: 86400        # 24 小时 (秒)
    issuer: deepseek-chat-demo
    redis-prefix: "auth:token:"      # Redis key 前缀

# MyBatis-Plus
mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl  # dev only
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
      id-type: auto
```

#### application-prod.yml

```yaml
spring:
  flyway:
    clean-disabled: true    # 生产禁止 clean
  ai:
    chat:
      memory:
        repository:
          jdbc:
            initialize-schema: never

mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.nologging.NoLoggingImpl

logging:
  level:
    com.demo.deepseekchat: INFO
```

---

## 3. 数据库设计

### 3.1 表总览

| 表名 | ORM | 说明 |
|------|-----|------|
| sys_user | MyBatis-Plus | 用户表 |
| sys_role | MyBatis-Plus | 角色表 |
| sys_permission | MyBatis-Plus | 权限表 |
| sys_user_role | MyBatis-Plus | 用户角色关联 |
| sys_role_permission | MyBatis-Plus | 角色权限关联 |
| system_prompt | MyBatis-Plus | 系统提示词（从 JPA 迁移） |
| model_params | MyBatis-Plus | 模型参数（从 JPA 迁移） |
| token_usage | MyBatis-Plus | 用量记录（从 JPA 迁移） |
| spring_ai_chat_memory | JdbcTemplate | Spring AI 自动管理，不动 |

### 3.2 ER 关系

```
sys_user (1) ──< (N) sys_user_role (N) >── (1) sys_role
sys_role (1) ──< (N) sys_role_permission (N) >── (1) sys_permission
```

### 3.3 表结构 DDL（PostgreSQL 原生语法）

#### sys_user — 用户表

```sql
CREATE TABLE sys_user (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL,
    password    VARCHAR(100) NOT NULL,
    nickname    VARCHAR(50),
    email       VARCHAR(100),
    phone       VARCHAR(20),
    avatar      VARCHAR(255),
    status      SMALLINT     NOT NULL DEFAULT 1,   -- 1:启用 0:禁用
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ,
    deleted     SMALLINT     NOT NULL DEFAULT 0    -- 0:正常 1:已删
);

-- 软删除感知的唯一约束
CREATE UNIQUE INDEX uk_user_username ON sys_user (username) WHERE deleted = 0;
CREATE UNIQUE INDEX uk_user_email ON sys_user (email) WHERE deleted = 0 AND email IS NOT NULL;
CREATE INDEX idx_user_status ON sys_user (status) WHERE deleted = 0;
```

#### sys_role — 角色表

```sql
CREATE TABLE sys_role (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    role_name   VARCHAR(50)  NOT NULL,             -- ADMIN, USER 等
    role_desc   VARCHAR(100),
    status      SMALLINT     NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ,
    deleted     SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_role_name ON sys_role (role_name) WHERE deleted = 0;
```

#### sys_permission — 权限表

```sql
CREATE TABLE sys_permission (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    permission_name  VARCHAR(50)  NOT NULL,        -- chat:send, user:manage 等
    permission_desc  VARCHAR(100),
    resource_type    VARCHAR(20)  NOT NULL,        -- API / MENU / BUTTON
    resource_key     VARCHAR(100),
    parent_id        BIGINT       DEFAULT NULL,    -- NULL 表示顶级
    status           SMALLINT     NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ,
    deleted          SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_permission_name ON sys_permission (permission_name) WHERE deleted = 0;
```

#### sys_user_role — 用户角色关联表

```sql
CREATE TABLE sys_user_role (
    user_id   BIGINT NOT NULL REFERENCES sys_user(id),
    role_id   BIGINT NOT NULL REFERENCES sys_role(id),
    PRIMARY KEY (user_id, role_id)
);
```

#### sys_role_permission — 角色权限关联表

```sql
CREATE TABLE sys_role_permission (
    role_id       BIGINT NOT NULL REFERENCES sys_role(id),
    permission_id BIGINT NOT NULL REFERENCES sys_permission(id),
    PRIMARY KEY (role_id, permission_id)
);
```

#### system_prompt — 系统提示词（从 JPA 迁移）

```sql
CREATE TABLE system_prompt (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    model_id    VARCHAR(100) NOT NULL,
    prompt_text TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX uk_system_prompt_model ON system_prompt (model_id);
```

#### model_params — 模型参数（从 JPA 迁移）

```sql
CREATE TABLE model_params (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    model_id      VARCHAR(100) NOT NULL,
    param_key     VARCHAR(100) NOT NULL,
    param_value   VARCHAR(500) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ
);

CREATE UNIQUE INDEX uk_model_params_key ON model_params (model_id, param_key);
```

#### token_usage — 用量记录（从 JPA 迁移）

```sql
CREATE TABLE token_usage (
    id                BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    conversation_id   VARCHAR(100),
    model_id          VARCHAR(100),
    prompt_tokens     BIGINT       DEFAULT 0,
    completion_tokens BIGINT       DEFAULT 0,
    total_tokens      BIGINT       DEFAULT 0,
    duration_ms       BIGINT       DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_usage_conversation ON token_usage (conversation_id);
CREATE INDEX idx_usage_model ON token_usage (model_id);
CREATE INDEX idx_usage_created ON token_usage (created_at);
```

### 3.4 初始数据

```sql
-- 角色
INSERT INTO sys_role (role_name, role_desc) VALUES ('ADMIN', '系统管理员');
INSERT INTO sys_role (role_name, role_desc) VALUES ('USER', '普通用户');

-- 权限
INSERT INTO sys_permission (permission_name, permission_desc, resource_type, resource_key) VALUES
('chat:send',           '发送聊天消息', 'API', 'POST /api/chat'),
('chat:stream',         '流式聊天',     'API', 'GET /api/chat/stream'),
('conversation:manage', '管理对话记录', 'API', '*'),
('model:config',        '配置模型参数', 'API', '*'),
('prompt:manage',       '管理系统提示词','API', '*'),
('usage:view',          '查看用量统计', 'API', '*'),
('user:manage',         '管理用户',     'API', '*'),
('role:manage',         '管理角色权限', 'API', '*');

-- ADMIN 角色拥有全部权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission;

-- USER 角色拥有基础权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 2, id FROM sys_permission
WHERE permission_name IN ('chat:send', 'chat:stream', 'conversation:manage', 'usage:view');

-- 初始管理员 (密码: admin123, BCrypt 加密)
-- 注意：实际部署时通过 Flyway afterMigrate 脚本或启动后首次初始化安全创建
INSERT INTO sys_user (username, password, nickname, status) VALUES
('admin', '$2a$10$EixZaYVK1fsbw1ZfbX3OXePaWxn96p36Kz2u0bF/8bF4W2vF4I0Gq', '系统管理员', 1);

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);
```

---

## 4. 项目结构

```
src/main/java/com/demo/deepseekchat/
├── security/                              # 安全模块（新）
│   ├── config/
│   │   ├── SecurityConfig.java            # Spring Security 配置
│   │   └── JwtProperties.java             # JWT 参数配置 (@ConfigurationProperties)
│   ├── filter/
│   │   └── JwtAuthenticationFilter.java   # JWT 认证过滤器
│   └── util/
│       └── JwtTokenProvider.java          # JWT 令牌生成/验证
├── user/                                  # 用户模块（新，MyBatis-Plus）
│   ├── entity/
│   │   ├── SysUser.java
│   │   ├── SysRole.java
│   │   ├── SysPermission.java
│   │   ├── SysUserRole.java
│   │   └── SysRolePermission.java
│   ├── mapper/
│   │   ├── SysUserMapper.java
│   │   ├── SysRoleMapper.java
│   │   ├── SysPermissionMapper.java
│   │   ├── SysUserRoleMapper.java
│   │   └── SysRolePermissionMapper.java
│   ├── service/
│   │   ├── SysUserService.java
│   │   ├── SysRoleService.java
│   │   ├── SysPermissionService.java
│   │   └── AuthService.java              # 认证服务（登录/注册/刷新/吊销）
│   ├── dto/
│   │   ├── LoginRequest.java
│   │   ├── RegisterRequest.java
│   │   ├── RefreshRequest.java
│   │   ├── LoginResponse.java
│   │   ├── UserInfoDTO.java
│   │   ├── UserUpdateRequest.java
│   │   └── ChangePasswordRequest.java
│   └── controller/
│       ├── AuthController.java            # 登录/注册/刷新/登出
│       └── UserController.java            # 用户管理（ADMIN）
├── model/                                 # 业务模型（MyBatis-Plus，从 JPA 迁移）
│   ├── entity/
│   │   ├── SystemPrompt.java              # 原 JPA Entity → MP Entity
│   │   ├── ModelParams.java
│   │   └── TokenUsage.java
│   ├── mapper/
│   │   ├── SystemPromptMapper.java
│   │   ├── ModelParamsMapper.java
│   │   └── TokenUsageMapper.java
│   ├── service/
│   │   ├── SystemPromptService.java       # 重构：JPA Repo → MP Mapper
│   │   ├── ModelParamsService.java
│   │   ├── UsageService.java
│   │   ├── ConversationService.java
│   │   └── ChatService.java
│   └── controller/
│       ├── ChatController.java
│       ├── ConversationController.java
│       ├── PromptController.java
│       ├── ModelParamsController.java
│       └── UsageController.java
├── config/
│   ├── MyBatisPlusConfig.java             # MP 配置（分页插件）
│   ├── RedisConfig.java                   # Redis 序列化配置
│   └── DeepSeekAutoConfiguration.java     # 已有
├── advisor/                               # Spring AI Advisor（已有）
├── chat/                                  # ChatClient（已有）
└── DeepseekChatApplication.java           # @MapperScan

src/main/resources/
├── db/migration/
│   ├── V1__init_user_module.sql           # sys_* 表 + 初始数据
│   ├── V2__migrate_jpa_to_mp.sql          # system_prompt / model_params / token_usage
│   └── R__refresh_admin_password.sql      # 可重复执行的管理员密码重置
└── mapper/                                # MyBatis XML（按需）
```

---

## 5. Redis 数据结构

### 5.1 Token 存储

```
Key:    auth:token:{userId}:{tokenId}
Value:  JSON { "roles": ["ADMIN"], "createdAt": 1704..., "device": "browser" }
TTL:    900s (15min) — access token
        86400s (24h) — refresh token

Key:    auth:refresh:{refreshToken}
Value:  userId
TTL:    86400s
```

### 5.2 权限缓存

```
Key:    auth:perms:{userId}
Value:  Set<permission_name>  e.g. {"chat:send", "user:manage", "*:*"}
TTL:    300s (5min)，权限变更时主动删除
```

### 5.3 登录限流

```
Key:    ratelimit:login:{ip}
Value:  计数器
TTL:    300s (5min 窗口)
```

### 5.4 用户状态缓存（吊销支持）

```
Key:    auth:status:{userId}
Value:  "active" | "disabled" | "deleted"
TTL:    与最长 token 一致 (24h)

# 密码变更 / 禁用用户 / 删除用户时写入，使旧 token 失效：
# 1. DEL auth:token:{userId}:*
# 2. DEL auth:perms:{userId}
# 3. SET auth:status:{userId} "disabled" EX 86400
```

---

## 6. API 设计

### 6.1 认证接口 `/api/auth`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/auth/register` | 公开 | 用户注册（可选关闭） |
| POST | `/api/auth/login` | 公开 | 登录，返回 access + refresh token |
| POST | `/api/auth/refresh` | 公开 | 刷新 access token |
| POST | `/api/auth/logout` | 登录 | 登出（Redis 吊销 token） |
| GET  | `/api/auth/me` | 登录 | 获取当前用户信息 + 权限 |

**LoginResponse**:
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshExpiresIn": 86400,
  "user": {
    "id": 1,
    "username": "admin",
    "nickname": "系统管理员",
    "roles": ["ADMIN"]
  }
}
```

> 注：移除 v1 的 `/api/users/self`，合并到 `/api/auth/me`。

### 6.2 用户管理接口 `/api/users`（仅 ADMIN）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET    | `/api/users` | user:manage | 用户列表（分页 + 过滤） |
| GET    | `/api/users/{id}` | user:manage | 用户详情 |
| PATCH  | `/api/users/{id}` | user:manage | 修改用户信息 |
| PATCH  | `/api/users/{id}/status` | user:manage | 启用/禁用用户 |
| PATCH  | `/api/users/{id}/roles` | user:manage | 分配角色 |
| DELETE | `/api/users/{id}` | user:manage | 删除用户（逻辑删除） |

### 6.3 当前用户接口 `/api/auth/me` 子资源

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| PATCH | `/api/auth/me/password` | 登录 | 修改密码（需旧密码验证） |
| PATCH | `/api/auth/me/profile` | 登录 | 修改个人信息 |

### 6.4 分页返回体约定

```json
{
  "content": [...],
  "page": 1,
  "size": 20,
  "total": 100,
  "totalPages": 5
}
```

---

## 7. 核心流程

### 7.1 认证流程（双 Token）

```
登录:
  Client ──POST /api/auth/login──> AuthController
                                      │
                                AuthService
                                      │
                           1. 查询用户 (MyBatis-Plus)
                           2. 校验用户状态 (Redis 缓存 + DB)
                           3. BCrypt 校验密码
                           4. 查询用户角色
                           5. 生成 access token (15min, 载荷: userId + roles)
                           6. 生成 refresh token (24h)
                           7. Redis 存储两个 token 元数据
                           8. 缓存用户权限到 Redis
                           9. 返回 LoginResponse
                                      │
  Client <──LoginResponse──────────────┘

刷新:
  Client ──POST /api/auth/refresh──> AuthController
                                       │
                                 AuthService
                                       │
                            1. 验证 refresh token 签名
                            2. 从 Redis 读取 refresh token 记录
                            3. 检查用户状态
                            4. 生成新 access token + 新 refresh token
                            5. 旧 refresh token 从 Redis 删除（轮换）
                            6. 返回新 token 对
                                       │
  Client <──New tokens─────────────────┘

吊销 (登出/禁用/改密):
  1. DEL auth:token:{userId}:*    — 清除所有 access token
  2. DEL auth:refresh:{token}     — 清除 refresh token
  3. DEL auth:perms:{userId}      — 清除权限缓存
  4. SET auth:status:{userId}     — 标记状态（使旧 token 即使在 TTL 内也失效）
```

### 7.2 请求鉴权流程

```
Request → JwtAuthenticationFilter
              │
         1. 提取 Authorization: Bearer {token}
         2. 验证 JWT 签名 + 过期时间
         3. 从 Redis 读取 auth:token:{userId}:{tokenId}
            - 不存在 → token 已被吊销 → 401
            - 存在 → 继续校验用户状态
         4. 从 Redis 读取 auth:status:{userId}
            - "disabled"/"deleted" → 401
            - 不存在/ "active" → 继续
         5. 从 Redis 读取 auth:perms:{userId}（缓存命中）
            - 未命中 → 查 DB → 写入 Redis
         6. 构建 Authentication:
            - principal: userId
            - authorities: [ROLE_ADMIN, chat:send, user:manage, ...]
              或 ADMIN 角色时: [*:*]
         7. 注入 SecurityContextHolder
              │
         Controller
              │
         @PreAuthorize("hasAuthority('chat:send')")
         @PreAuthorize("hasAuthority('*:*')")  // 管理员通配
```

### 7.3 权限实时生效

```
管理员修改角色权限:
  1. 编程式事务更新 sys_role_permission
  2. 事务提交成功后
  3. 查询该角色下的所有 userId
  4. 批量 DEL auth:perms:{userId}
  5. 下次请求自动从 DB 重新加载权限到 Redis
```

---

## 8. 事务管理

### 8.1 编程式事务

统一使用 `TransactionTemplate`，不使用 `@Transactional`。

```java
@Configuration
public class TransactionConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
```

### 8.2 事务管理器

移除 JPA 后，Spring Boot 自动配置 `DataSourceTransactionManager`，MyBatis-Plus 直接使用，无需额外配置。

> 如果 Spring AI 的 JDBC ChatMemory 依赖间接引入了 JPA 相关包，容忍其存在但不在业务代码中使用。

---

## 9. 安全设计

### 9.1 Spring Security 配置

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/register",
                                 "/api/auth/refresh").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, authEx) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"未认证\",\"message\":\"" + authEx.getMessage() + "\"}");
                })
                .accessDeniedHandler((req, res, accessEx) -> {
                    res.setStatus(403);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"权限不足\"}");
                })
            )
            .build();
    }
}
```

### 9.2 CORS 配置

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("http://localhost:3000")); // 前端地址
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
}
```

### 9.3 登录限流

复用现有 `TokenBucketLimiter`，在 `/api/auth/login` 和 `/api/auth/register` 上增加 IP 维度限流：
- 同一 IP 5 分钟内最多 10 次登录尝试
- 超限返回 429 Too Many Requests

### 9.4 密码策略

- 最小长度 8 位
- 必须包含字母 + 数字
- BCrypt 强度 10（默认）

---

## 10. 实施计划

### Phase 0：基础设施 + JPA 迁移（1 次提交）

- [ ] pom.xml：移除 JPA + Lombok，添加 MyBatis-Plus + Redis + Security + JJWT + Flyway
- [ ] application.yml：重写配置（Flyway + Redis + JWT + MyBatis-Plus）
- [ ] 移除 JPA 相关代码（Repository 接口、Entity 注解）
- [ ] 所有原 JPA Entity → MyBatis-Plus Entity（@TableName, @TableId, @TableLogic）
- [ ] 所有原 JPA Repository → MyBatis-Plus Mapper（extends BaseMapper）
- [ ] 所有 Service 中的 JPA 调用 → MyBatis-Plus 调用
- [ ] MyBatisPlusConfig.java（分页插件）
- [ ] RedisConfig.java
- [ ] TransactionConfig.java（TransactionTemplate Bean）
- [ ] Flyway 迁移脚本 V1 + V2
- [ ] Docker 启动 Redis 容器
- [ ] 编译验证
- **验收标准**：项目编译通过，原有 JPA 功能全部等价迁移到 MyBatis-Plus

### Phase 1：用户模块实体 + Mapper（1 次提交）

- [ ] 5 个 Entity（SysUser, SysRole, SysPermission, SysUserRole, SysRolePermission）
- [ ] 5 个 Mapper
- [ ] Flyway V1 迁移脚本（sys_* 建表 + 初始数据 + 管理员账号）
- **验收标准**：Flyway 迁移成功，建表 + 初始数据就位

### Phase 2：认证服务 + JWT + Redis（1 次提交）

- [ ] JwtProperties（@ConfigurationProperties）
- [ ] JwtTokenProvider（生成/验证）
- [ ] AuthService（登录/注册/刷新/登出）
- [ ] Redis token 存储逻辑
- [ ] Redis 权限缓存逻辑
- [ ] 密码 BCrypt 工具
- **验收标准**：登录返回双 token，Redis 中可查到 token 记录和权限缓存

### Phase 3：安全框架（1 次提交）

- [ ] SecurityConfig（SecurityFilterChain）
- [ ] JwtAuthenticationFilter
- [ ] 登录限流（复用 TokenBucketLimiter）
- [ ] CORS 配置
- [ ] 401/403 统一错误处理
- [ ] 公开接口白名单
- [ ] 全局异常处理器更新
- **验收标准**：未认证请求返回 401，认证后可访问受保护接口

### Phase 4：Controller 层（1 次提交）

- [ ] AuthController（login/register/refresh/logout/me）
- [ ] UserController（CRUD + 分页）
- [ ] 所有现有 Controller 加 @PreAuthorize
- [ ] 分页返回体统一
- **验收标准**：完整认证流程可用，权限校验生效

### Phase 5：权限管理 + 吊销（1 次提交）

- [ ] SysRoleService + SysPermissionService
- [ ] 角色分配接口
- [ ] 权限变更后缓存失效
- [ ] 禁用用户 → token 吊销
- [ ] 改密 → token 吊销
- **验收标准**：权限变更实时生效，吊销后旧 token 立即失效

### Phase 6：集成验证 + 推送（1 次提交）

- [ ] 全量编译
- [ ] Docker 环境 PostgreSQL + Redis 验证
- [ ] 基础功能冒烟测试
- [ ] Git 提交推送
- **验收标准**：全流程跑通

---

## 11. 扩展性说明

未来如需扩展为多角色、多级权限体系：

- **新增角色**：INSERT sys_role + sys_role_permission，无需改代码
- **新增权限**：INSERT sys_permission，在 Controller 方法上标注 `@PreAuthorize("hasAuthority('xxx')")`
- **数据权限**：可在权限表中增加 scope 字段，配合 AOP 实现数据范围过滤
- **菜单树**：sys_permission 已有 parent_id（NULL = 顶级），支持树形结构
- **角色继承**：sys_role 可扩展 parent_role_id 实现层级
- **多租户**：在 sys_user 加 tenant_id，配合 MyBatis-Plus TenantLineInnerInterceptor

---

## 12. 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| JPA → MyBatis-Plus 迁移遗漏 | Phase 0 专项迁移，编译 + 运行验证 |
| Flyway 与 Spring AI auto-init 冲突 | Spring AI 的 `spring_ai_chat_memory` 表由其自管，Flyway 只管业务表 |
| Redis 不可用导致认证失败 | JwtAuthenticationFilter 中 Redis 异常时降级为 DB 查询（最终一致性） |
| Refresh token 泄漏 | 轮换机制：每次 refresh 后旧 token 立即失效 |
| 初始管理员密码硬编码 | Flyway afterMigrate 脚本或环境变量注入，生产环境首次启动强制修改 |
