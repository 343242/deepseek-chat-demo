# RBAC 用户模块设计文档

> 版本: 1.0 | 日期: 2026-05-07 | ORM: MyBatis-Plus 3.5.16

---

## 1. 模块概述

为 deepseek-chat-demo 引入基于 RBAC（Role-Based Access Control）的用户权限系统。

**当前阶段**：管理员 + 普通用户两种角色，满足基本权限隔离需求。
**扩展性保证**：数据模型和代码结构支持未来新增角色、权限、菜单等，无需重构。

### 1.1 设计原则

- **用户模块使用 MyBatis-Plus**，与现有 Spring Data JPA（chat memory 相关表）共存
- **认证采用 JWT**，无状态，适合前后端分离
- **密码存储使用 BCrypt**，不可逆加密
- **编程式事务**控制，不使用 `@Transactional` 注解
- **接口统一前缀** `/api/auth` 和 `/api/users`

---

## 2. 数据库设计

### 2.1 ER 关系

```
sys_user (1) ──< (N) sys_user_role (N) >── (1) sys_role
sys_role (1) ──< (N) sys_role_permission (N) >── (1) sys_permission
```

### 2.2 表结构

#### sys_user — 用户表

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| username | VARCHAR(50) | UNIQUE, NOT NULL | 用户名（登录用） |
| password | VARCHAR(100) | NOT NULL | BCrypt 加密密码 |
| nickname | VARCHAR(50) | | 昵称（显示用） |
| email | VARCHAR(100) | UNIQUE | 邮箱 |
| phone | VARCHAR(20) | | 手机号 |
| avatar | VARCHAR(255) | | 头像 URL |
| status | TINYINT | NOT NULL, DEFAULT 1 | 状态：1-启用 0-禁用 |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | 创建时间 |
| updated_at | TIMESTAMP | | 更新时间 |
| deleted | TINYINT | NOT NULL, DEFAULT 0 | 逻辑删除：0-未删 1-已删 |

#### sys_role — 角色表

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| role_name | VARCHAR(50) | UNIQUE, NOT NULL | 角色标识（如 ADMIN, USER） |
| role_desc | VARCHAR(100) | | 角色描述 |
| status | TINYINT | NOT NULL, DEFAULT 1 | 状态：1-启用 0-禁用 |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | 创建时间 |
| updated_at | TIMESTAMP | | 更新时间 |
| deleted | TINYINT | NOT NULL, DEFAULT 0 | 逻辑删除 |

#### sys_permission — 权限表

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| permission_name | VARCHAR(50) | NOT NULL | 权限名称（如 chat:send, user:manage） |
| permission_desc | VARCHAR(100) | | 权限描述 |
| resource_type | VARCHAR(20) | NOT NULL | 资源类型：API / MENU / BUTTON |
| resource_key | VARCHAR(100) | | 资源标识（如 POST /api/chat） |
| parent_id | BIGINT | DEFAULT 0 | 父权限 ID（预留树形结构） |
| status | TINYINT | NOT NULL, DEFAULT 1 | 状态 |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | 创建时间 |
| updated_at | TIMESTAMP | | 更新时间 |
| deleted | TINYINT | NOT NULL, DEFAULT 0 | 逻辑删除 |

#### sys_user_role — 用户角色关联表

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| user_id | BIGINT | NOT NULL, INDEX | 用户 ID |
| role_id | BIGINT | NOT NULL, INDEX | 角色 ID |

> UNIQUE(user_id, role_id)

#### sys_role_permission — 角色权限关联表

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| role_id | BIGINT | NOT NULL, INDEX | 角色 ID |
| permission_id | BIGINT | NOT NULL, INDEX | 权限 ID |

> UNIQUE(role_id, permission_id)

### 2.3 初始数据

```sql
-- 角色
INSERT INTO sys_role (role_name, role_desc) VALUES ('ADMIN', '系统管理员');
INSERT INTO sys_role (role_name, role_desc) VALUES ('USER', '普通用户');

-- 权限（按模块划分，方便扩展）
INSERT INTO sys_permission (permission_name, permission_desc, resource_type, resource_key) VALUES
('chat:send', '发送聊天消息', 'API', '*'),
('conversation:manage', '管理对话记录', 'API', '*'),
('model:config', '配置模型参数', 'API', '*'),
('prompt:manage', '管理系统提示词', 'API', '*'),
('usage:view', '查看用量统计', 'API', '*'),
('user:manage', '管理用户', 'API', '*'),
('role:manage', '管理角色', 'API', '*');

-- ADMIN 角色拥有全部权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission;

-- USER 角色拥有基础权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 2, id FROM sys_permission WHERE permission_name IN ('chat:send', 'conversation:manage', 'usage:view');
```

---

## 3. 项目结构

```
src/main/java/com/demo/deepseekchat/
├── security/                          # 安全模块（新）
│   ├── config/
│   │   ├── SecurityConfig.java        # Spring Security 配置
│   │   └── JwtConfig.java             # JWT 参数配置
│   ├── filter/
│   │   └── JwtAuthenticationFilter.java  # JWT 认证过滤器
│   ├── util/
│   │   └── JwtTokenProvider.java      # JWT 令牌生成/验证
│   └── annotation/
│       └── RequirePermission.java     # 自定义权限注解
├── user/                              # 用户模块（新，MyBatis-Plus）
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
│   │   └── AuthService.java          # 认证服务（登录/注册）
│   ├── dto/
│   │   ├── LoginRequest.java
│   │   ├── RegisterRequest.java
│   │   ├── LoginResponse.java
│   │   ├── UserInfoDTO.java
│   │   ├── UserUpdateRequest.java
│   │   └── ChangePasswordRequest.java
│   └── controller/
│       ├── AuthController.java        # 登录/注册/刷新 token
│       └── UserController.java        # 用户管理（ADMIN）
├── config/
│   └── MyBatisPlusConfig.java         # MyBatis-Plus 配置（新）
└── DeepseekChatApplication.java       # 加 @MapperScan
```

---

## 4. API 设计

### 4.1 认证接口 `/api/auth`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| POST | `/api/auth/register` | 公开 | 用户注册 |
| POST | `/api/auth/login` | 公开 | 用户登录，返回 JWT |
| POST | `/api/auth/refresh` | 登录 | 刷新 JWT |
| POST | `/api/auth/logout` | 登录 | 登出（客户端清除 token） |
| GET  | `/api/auth/me` | 登录 | 获取当前用户信息 |

**LoginResponse**:
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 86400,
  "user": {
    "id": 1,
    "username": "admin",
    "nickname": "管理员",
    "roles": ["ADMIN"],
    "permissions": ["chat:send", "user:manage", ...]
  }
}
```

### 4.2 用户管理接口 `/api/users`（仅 ADMIN）

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET    | `/api/users` | user:manage | 用户列表（分页） |
| GET    | `/api/users/{id}` | user:manage | 用户详情 |
| PUT    | `/api/users/{id}` | user:manage | 修改用户信息 |
| PUT    | `/api/users/{id}/status` | user:manage | 启用/禁用用户 |
| PUT    | `/api/users/{id}/roles` | user:manage | 分配角色 |
| DELETE | `/api/users/{id}` | user:manage | 删除用户（逻辑删除） |

### 4.3 当前用户接口 `/api/users/self`

| 方法 | 路径 | 权限 | 说明 |
|------|------|------|------|
| GET  | `/api/users/self` | 登录 | 获取个人信息 |
| PUT  | `/api/users/self` | 登录 | 修改个人信息 |
| PUT  | `/api/users/self/password` | 登录 | 修改密码 |

---

## 5. 核心流程

### 5.1 认证流程

```
Client ──POST /api/auth/login──> AuthController
                                    │
                              AuthService
                                    │
                         1. 查询用户（MyBatis-Plus）
                         2. BCrypt 校验密码
                         3. 查询用户角色 + 权限
                         4. 生成 JWT（含 userId, roles, permissions）
                         5. 返回 LoginResponse
                                    │
Client <──LoginResponse──────────────┘

后续请求:
Client ──Authorization: Bearer xxx──> JwtAuthenticationFilter
                                        │
                                  验证 JWT
                                  解析权限
                                  注入 SecurityContext
                                        │
                                  Controller → @RequirePermission("chat:send")
```

### 5.2 权限校验流程

```
@RequirePermission("user:manage") 注解标记在 Controller 方法上

JwtAuthenticationFilter 解析 JWT → 将权限列表存入 SecurityContext

自定义 PermissionInterceptor 拦截请求:
  1. 获取注解声明的权限
  2. 从 SecurityContext 获取当前用户权限列表
  3. ADMIN 角色直接放行（超级管理员）
  4. 普通用户检查是否包含所需权限
  5. 不通过 → 403 Forbidden
```

### 5.3 用户注册流程

```
POST /api/auth/register
  1. 校验参数（用户名唯一、密码强度）
  2. BCrypt 加密密码
  3. 插入 sys_user（编程式事务）
  4. 插入 sys_user_role（默认分配 USER 角色，同一事务）
  5. 返回用户信息（不含密码）
```

---

## 6. 依赖变更

### pom.xml 新增

```xml
<!-- MyBatis-Plus 3.5.16 (Spring Boot 3) -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.16</version>
</dependency>

<!-- Spring Security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- JWT (JJWT) -->
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
```

### pom.xml 变更

- 移除 Lombok 依赖（项目未使用）
- 保留 Spring Data JPA（chat memory / token_usage 等表仍用 JPA）
- 新增 MyBatis-Plus（用户模块专用）

> **JPA 与 MyBatis-Plus 共存**：两个框架操作不同的表，互不干扰。
> JPA 管：`system_prompt`, `model_params`, `token_usage` + Spring AI 自动管理的 `spring_ai_chat_memory`
> MyBatis-Plus 管：`sys_user`, `sys_role`, `sys_permission`, `sys_user_role`, `sys_role_permission`

---

## 7. 配置变更

### application.yml 新增

```yaml
# JWT
app:
  jwt:
    secret: ${JWT_SECRET:myDefaultSecretKeyForDevOnlyMustBe32CharsLong!!}
    expiration: 86400          # 24h (秒)
    refresh-expiration: 604800 # 7天 (秒)
    issuer: deepseek-chat-demo

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

### prod profile

```yaml
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.nologging.NoLoggingImpl
```

---

## 8. MyBatis-Plus 与 JPA 共存注意事项

1. **实体包路径隔离**：
   - JPA 实体：`model.entity.*`（现有）
   - MyBatis-Plus 实体：`user.entity.*`（新增）
   - 避免同一个包下混合两种 ORM 的实体

2. **事务管理器共享**：Spring Boot 自动配置的 `DataSourceTransactionManager` 两者共用，编程式事务通过 `PlatformTransactionManager` 统一控制

3. **@MapperScan**：仅扫描 `com.demo.deepseekchat.user.mapper` 包

4. **EntityManager vs SqlSession**：不同操作用不同的 Session，不会冲突

---

## 9. 实施计划

### Phase 1：基础设施（预计 1 次提交）

- [ ] pom.xml 加依赖（MyBatis-Plus、Spring Security、JJWT）
- [ ] 移除 Lombok 依赖
- [ ] application.yml 加 JWT + MyBatis-Plus 配置
- [ ] MyBatisPlusConfig.java（分页插件、逻辑删除等）
- [ ] DeepseekChatApplication 加 `@MapperScan`
- [ ] SQL 初始化脚本 `schema/user-module.sql`

### Phase 2：用户模块实体 + Mapper（预计 1 次提交）

- [ ] 5 个 Entity（SysUser, SysRole, SysPermission, SysUserRole, SysRolePermission）
- [ ] 5 个 Mapper（继承 BaseMapper）
- [ ] 初始数据 SQL

### Phase 3：认证服务（预计 1 次提交）

- [ ] JwtTokenProvider（生成/验证/刷新）
- [ ] JwtConfig（配置属性）
- [ ] AuthService（登录/注册/刷新）
- [ ] AuthController（4 个端点）

### Phase 4：权限框架（预计 1 次提交）

- [ ] SecurityConfig（Spring Security 配置）
- [ ] JwtAuthenticationFilter（过滤器）
- [ ] RequirePermission 注解 + PermissionInterceptor
- [ ] 公开接口白名单

### Phase 5：用户管理（预计 1 次提交）

- [ ] SysUserService（CRUD + 编程式事务）
- [ ] UserController（用户列表/详情/修改/删除）
- [ ] UserController.self（个人信息/改密码）
- [ ] SysRoleService + SysPermissionService（辅助查询）

### Phase 6：集成测试 + 提交

- [ ] 编译验证
- [ ] Git 提交推送

---

## 10. 扩展性说明

未来如需扩展为多角色、多级权限体系：

- **新增角色**：只需 INSERT sys_role + sys_role_permission，无需改代码
- **新增权限**：INSERT sys_permission，然后在 Controller 方法上标注 `@RequirePermission("xxx")`
- **数据权限**：可在注解中增加数据范围参数（如 `@RequirePermission(value="user:manage", scope="DEPT")`）
- **菜单树**：sys_permission 已预留 parent_id 字段，支持树形结构
- **角色继承**：sys_role 可扩展 parent_role_id 实现角色层级
