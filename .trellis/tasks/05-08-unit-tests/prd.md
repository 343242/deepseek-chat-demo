# PRD: Unit Tests for User & Security Modules

## Scope

为以下模块编写单元测试，覆盖核心业务逻辑和刚修复的 7 个 Codex 审计问题：

### Service 层测试（优先级最高）

1. **AuthService** — 登录/注册/密码复杂度/限流交互/验证码/刷新令牌
2. **SysUserService** — 用户管理/状态变更/角色分配（含去重+存在性校验）
3. **SysRoleService** — 角色 CRUD/权限分配（含去重+存在性校验）
4. **SysPermissionService** — 权限创建（含双字段唯一性校验）
5. **TokenCacheService** — 限流逻辑（off-by-one 修复验证）/ token 存储/吊销
6. **CaptchaService** — 验证码生成/校验/限流

### DTO 校验测试

7. **RegisterRequest** — @NotBlank/@Size/@Email 约束
8. **UserUpdateRequest** — @Email/@Size/@Pattern 约束
9. **AssignRolesRequest** — @NotEmpty/@Size 约束
10. **AssignPermissionsRequest** — @NotEmpty/@Size 约束

### Controller 层测试（集成测试风格）

11. **AuthController** — 注册/登录/刷新/登出端点
12. **UserController** — 用户管理端点（含 status 枚举校验）
13. **RoleController** — 角色管理端点（含 DTO 校验）

## Technical Approach

- 使用 Spring Boot 3.x + Mockito + JUnit 5
- Service 测试用 `@ExtendWith(MockitoExtension.class)`，mock mapper 和 Redis
- Controller 测试用 `@WebMvcTest` + `@MockBean`
- DTO 校验测试用 `Validator` 直接验证

## Non-Goals

- 不涉及数据库集成测试（需要 Testcontainers，后续迭代）
- 不涉及 chat 模块测试（范围不同）
- 不涉及端到端测试
