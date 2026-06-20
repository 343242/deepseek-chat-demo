# Implement — user 模块 review 剩余项修复

依据 `docs/code-review/2026-06-20-user-module-review.md` 的 MEDIUM/LOW 项。

## 修复清单

### AuthServiceImpl.java（M1/M2/M6/M7/M8/L3/L7）
- [ ] M1: register 的 RuntimeException → ServiceException（默认角色缺失用 ROLE_NOT_FOUND+msg；newUser==null 改 ServiceException）
- [ ] M7: login 中先 validateCaptcha 再 checkAndIncrementLoginAttempts（错误验证码不烧登录次数）
- [ ] M8: rate-limit 触发 + 登录失败记 WARN（含 ip/userId 上下文，不泄密码）
- [ ] M6: register 加 IP 限流（AuthService.register 增 ip 参数，复用 checkAndIncrementLoginAttempts）
- [ ] L3: "USER" 提取 `DEFAULT_ROLE_NAME` 常量
- [ ] L7: nickname 空白串回退用户名（isBlank 判断）
- [ ] M2: changePassword 的 Redis 调用失败记 WARN（try-catch 包裹 revoke/evict）

### AuthService.java / AuthController.java
- [ ] M6: register 签名加 `String ip`；controller 传 `httpRequest.getRemoteAddr()`

### UserStatus.java（M11）
- [ ] 删除未被调用的 fromCode（含 IAE）

### CookieTokenManager.java（L2）
- [ ] buildExpiredCookie 补 setSecure(cookieSecure)

### DTO（L4/L6）
- [ ] ChangePasswordRequest / RegisterRequest: newPassword/password 补 @Size(max=72)
- [ ] UserUpdateRequest.avatar: 补 URL scheme 校验（@Pattern 禁 javascript:/data:）

### SysUserServiceImpl.java（L9/L10/L11/M2）
- [ ] L9: listUsers 去掉手动 deleted=0（@TableLogic 已处理）
- [ ] L11: status 比较避免拆箱歧义（用 UserStatus.x.code 明确）
- [ ] L10: assignRoles 角色/权限存在性校验移入事务
- [ ] M2: updateUserStatus/deleteUser 的 Redis 调用失败记 WARN

### SysRoleServiceImpl.java（L10）
- [ ] L10: assignPermissions 权限校验移入事务

### RoleController.java（L12）
- [ ] assignPermissions 返回 Map → 新增 AssignPermissionsResult record

### SysPermissionService/Impl/Test（M4）
- [ ] 删除死代码 createPermission/deletePermission（无 API 入口，消除 latent 授权 bug）

### application-stable.yml（M5）
- [ ] 补 server.forward-headers-strategy: framework（假设部署在受信反代后）

## 明确不改（文档说明）
- M9 @NotEmpty：保留为安全护栏（防误清空角色导致自锁）
- M10 裸 Integer status：Service 层 isValid 已校验，足够
- L1 validateToken type：调用方各自校验的设计，改动风险>收益
- L5 refresh body 取值：保留向后兼容
- L13 RESTful 动词：破坏性 API 变更，需前端协同
- L14/L15：低价值/有意设计

## 验证
- `./mvnw test` 全量绿
- `detect_changes` 受影响范围仅预期
- commit `fix(user): ...` + push + archive

## 回滚
每逻辑组一个 commit；git revert 单点。
