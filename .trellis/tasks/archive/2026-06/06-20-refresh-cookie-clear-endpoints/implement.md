# Implement — L5 refresh cookie-only + M9 清空端点

## L5（破坏性，已确认纯浏览器）
- AuthController.refresh 改为只从 cookie 取 refresh token，去掉 `RefreshRequest` body 参数与 `resolveRefreshToken` 私有方法
- 删除 `dto/RefreshRequest.java`（变死代码）

## M9（新增端点，assign 仍保留 @NotEmpty 作护栏）
- SysUserService + Impl：`clearRoles(Long id)` → 事务内 deleteByUserId + 提交后 evict，返回 `RoleAssignResult(id, [], "角色已清空")`
- SysRoleService + Impl：`clearPermissions(Long roleId)` → 事务内 deleteByRoleId + 提交后驱逐受影响用户缓存，返回 `AssignPermissionsResult(roleId, [], "权限已清空")`
- UserController：`POST /{id}/roles/clear`
- RoleController：`POST /{id}/permissions/clear`
- 注意：管理员显式操作，@PreAuthorize 已 gating；自锁风险（清自己角色）在注释提示

## 测试
- SysUserServiceTest.clearRoles_success
- SysRoleServiceTest.clearPermissions_success

## 验证
mvnw test 全绿；detect_changes；commit + push + archive
