# Design — user 模块 review 修复

> 详细审查依据见 `docs/code-review/2026-06-20-user-module-review.md`。本文件只记关键设计决策。

## R1. deleteRole 事务 + 驱逐顺序

当前 `SysRoleServiceImpl.java:87-101`：evict 循环 → 3 条 delete，无事务、驱逐在删除前。

改后：
```java
List<Long> userIds = userRoleMapper.selectUserIdsByRoleId(roleId);  // 提交前捕获
transactionTemplate.executeWithoutResult(status -> {
    rolePermissionMapper.deleteByRoleId(roleId);
    userRoleMapper.deleteByRoleId(roleId);
    roleMapper.deleteById(roleId);
});
userIds.forEach(tokenCacheService::evictUserPermissions);            // 提交后驱逐
```
- 事务保证三表原子；驱逐移到提交后（修复 M3 顺序竞态）。
- userIds 必须在删除前取（删完 `selectUserIdsByRoleId` 返回空）。

## R2. Entity → VO

新增 record（`user/dto/`）：
- `RoleVO(Long id, String roleName, String roleDesc, Integer status, OffsetDateTime createdAt, OffsetDateTime updatedAt)` — 剔除 `deleted`
- `PermissionVO(Long id, String permissionName, String permissionDesc, String resourceType, String resourceKey, Long parentId, Integer status)` — 剔除 `deleted`/时间戳（管理视图不需要；若需可加）

签名变更（内部调用方仅 RoleController，无内部符号依赖返回值）：
- `SysRoleService`: `listRoles()→List<RoleVO>`、`createRole/updateRole→RoleVO`、`getRolePermissions→List<PermissionVO>`
- `RoleController`: 同步返回类型
- `RoleDetailVO`: `(RoleVO role, List<PermissionVO> permissions)`

转换：在 `SysRoleServiceImpl` 加 private static `toRoleVO`/`toPermissionVO`（仿 `SysUserServiceImpl.toUserVO`）。

**契约影响**：API 响应去掉 `deleted` 字段 —— 前端本不应依赖此内部字段；需在 PR/commit 说明。

## R3. mapper role-status 过滤（谓词收窄，不改签名）

- `SysRoleMapper.xml selectByIds`：`WHERE deleted = 0 AND status = 1 AND id IN (...)`
- `SysUserRoleMapper.xml selectRoleIdsByUserId`：join sys_role
  ```sql
  SELECT ur.role_id FROM sys_user_role ur
  INNER JOIN sys_role r ON ur.role_id = r.id
  WHERE ur.user_id = #{userId} AND r.status = 1 AND r.deleted = 0
  ```
- `SysRolePermissionMapper.xml selectPermissionsByRoleIds`：加 join sys_role + `r.status=1 AND r.deleted=0`（与现有 `p.status=1 AND p.deleted=0` 并列，纵深防御）

**安全性论证**：纯收窄，调用方（`getRoleNames`、`loadUserPermissions`）本就处理"少于入参"的结果，不会因少返回而 NPE/越界。当前无禁用角色接口，故行为对线上等价（status 恒为 1）—— 改动是为将来禁用角色功能预置正确语义。

## R4. cookie-secure 配置

`application-stable.yml` 与 `application-evaluation.yml` 的 `app.jwt` 块加 `cookie-secure: true`。dev 保持默认 false（本地 HTTP）。fail-fast 强制（仿 JWT secret）作为 follow-up，不在本任务（避免扩大代码改动面）。

## 风险与回滚

- R3 在 CRITICAL 路径 → 全量 `mvnw test` 兜底。
- 回滚：每个修复独立 commit，需回退单点用 `git revert <sha>`。
