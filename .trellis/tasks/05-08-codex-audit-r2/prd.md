# PRD: Codex Audit Round 2 — User Module Bug Fixes

## Background

Codex 对 user 模块进行了第二轮审计，发现 7 个 bug + 2 个设计风险。本次修复 7 个 bug，设计风险记录待后续迭代。

## Scope

### Bug 1: 登录限流 off-by-one（P0）

**现状**: `TokenCacheService.isLoginRateLimited()` 用 `> 10`，意味着第 11 次请求在 `isLoginRateLimited` 返回 false 后被放行，increment 到 11，第 12 次才真正封禁。
**修复**: 改为 `>= 10`。同步更新 `getRemainingLoginAttempts` 的计算逻辑。

### Bug 2: 用户 status 字段收敛（P2，已部分修复）

**现状**: Controller 已有 `UserStatus.isValid()` 校验，但 SysUserService.updateUserStatus 内部用 `status == 0` 魔数判断。
**修复**: SysUserService 内部改用 `UserStatus.DISABLED.getCode()` 提升可读性。

### Bug 3: AssignRolesRequest 无校验（P1）

**现状**: DTO 无任何约束；Service 层盲插 roleId，不检查角色是否存在、不处理重复 ID。
**修复**:
- DTO 加 `@NotEmpty` + `@Size(max=20)` 约束
- Service 层去重 roleIds
- 校验所有 roleId 在数据库中存在
- 角色不存在时抛 BusinessException

### Bug 4: 角色权限分配同类缺陷（P1）

**现状**: RoleController.assignPermissions 用裸 `Map<String, List<Long>>` 接收，无 DTO、无校验。
**修复**:
- 新建 `AssignPermissionsRequest` DTO（带 `@NotEmpty` + `@Size`）
- Service 层去重 permissionIds
- 校验所有 permissionId 在数据库中存在
- 权限不存在时抛 BusinessException

### Bug 5: Cookie setSecure 硬编码 false（P1）

**现状**: AuthController.setTokenCookies 硬编码 `setSecure(false)`。
**修复**: 从配置读取 `jwt.cookie-secure`，默认 false（开发环境），生产环境通过配置切换。JwtProperties 新增 `cookieSecure` 字段。

### Bug 6: RegisterRequest 字段长度约束不完整（P1）

**现状**: username 缺 `@Size(max=50)`，email 缺 `@Size(max=100)`。
**修复**: 添加对应注解。

### Bug 7: 权限唯一性校验字段不一致（P1）

**现状**: `SysPermissionService.createPermission` 用 `resourceKey` 做唯一性校验，但数据库唯一索引可能在 `permission_name` 上。
**修复**: 同时校验 `permissionName` 和 `resourceKey` 两个字段的唯一性。

## Non-Goals

- 设计风险 1（混合认证模型）和设计风险 2（管理接口暴露实体）不在本次范围
- 不涉及前端或第三方客户端对接
- 不涉及新增 API endpoint

## Acceptance Criteria

- [ ] 所有 bug 修复后编译通过（`mvn compile`）
- [ ] 限流逻辑：第 10 次尝试被封禁，不是第 12 次
- [ ] AssignRolesRequest/AssignPermissionsRequest 有 DTO 校验
- [ ] Cookie secure 标志可配置
- [ ] RegisterRequest 有完整的 @Size 约束
- [ ] SysPermissionService 同时校验 permissionName 和 resourceKey
- [ ] git commit + push
