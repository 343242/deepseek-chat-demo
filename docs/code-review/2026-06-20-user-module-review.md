# 用户与权限模块 Code Review 报告

- **日期**：2026-06-20
- **范围**：`src/main/java/com/smart/rag/user/` 全部 38 个文件 + 关联 `infrastructure` 调用面（补查）
- **依据**：`.trellis/spec/backend/`（code-review-checklist / quality-guidelines / error-handling / database-guidelines / directory-structure / logging-guidelines）
- **方法**：两路并行 —— 主审（按 spec）+ `ecc:java-reviewer` agent，结果交叉比对去重纠偏
- **结论**：架构清晰，安全基本面扎实；存在 1 处事务缺陷、1 处 spec 硬违规、若干潜在授权/一致性缺口

---

## 🏁 Top 3 必须先修

1. **【HIGH】`deleteRole` 三表删除无事务** — `SysRoleServiceImpl.java:87-101`（写入在 98-100），包进 `transactionTemplate.executeWithoutResult`，与同文件 `assignPermissions:119` 对齐。
2. **【HIGH】Role/Permission Entity 直返前端** — `RoleController.java:36,46,51,62,78` + `RoleDetailVO.java:11-14`。补 `RoleVO`/`PermissionVO`，杜绝 `deleted`/内部字段泄漏。
3. **【HIGH·latent】禁用角色的权限仍生效 + 角色名仍进 JWT** — 三个 mapper 不过滤 `r.status`，趁未暴露先堵。

> 第 4 顺位：**【MEDIUM·配置】生产 Cookie 缺 Secure 标志** — `application-stable.yml` 未设 `app.jwt.cookie-secure: true`。

---

## 🔎 补查：`infrastructure` 调用面（agent 未覆盖项，本轮已补）

### ✅ 确认良好的安全实现

| 项 | 证据 | 评价 |
|---|---|---|
| Cookie `HttpOnly` | `CookieTokenManager.java:61,71,82` | ✓ |
| Cookie `SameSite=Lax` | `CookieTokenManager.java:65,75,84` | ✓ —— CSRF（`SecurityConfig.java:46` 已 disable）由它承接，设计自洽 |
| Cookie Path 收窄 | access=`/api`（:63），refresh=`/api/auth/refresh`（:73） | ✓ —— refresh cookie 仅发往刷新端点，纵深防御漂亮 |
| JWT 密钥强度强校验 | `JwtTokenProvider.java:34-48` `@PostConstruct`，<32 字符 / 非 dev 用已知默认值 → fail-fast | ✓ 扎实 |
| 生产强制显式密钥 | `application-stable.yml:65` `secret: ${JWT_SECRET}`（无默认），dev 才有默认（:73） | ✓ |
| JWT 签名 + issuer 校验 | `JwtTokenProvider.java:128-135` `verifyWith` + `requireIssuer` | ✓ |
| 每请求 jti 撤销校验 | `JwtAuthenticationFilter.java:70-74` `isAccessTokenValid(userId, jti)` | ✓ —— access token 可主动撤销，非纯无状态 |
| 每请求用户状态校验 | `JwtAuthenticationFilter.java:77-81` 读 Redis `auth:status`，disabled/deleted 拒绝 | ✓ —— 兜底了 disable/delete 的跨存储一致性窗口 |
| 权限每请求重算 | `JwtAuthenticationFilter.java:84-92` 从缓存/DB 取 permissions → authorities | ✓ —— `hasAuthority(...)` 类授权是新鲜的 |
| SQL 全部 `#{}` 参数化 | 5 个 mapper XML 无一处 `${}` | ✓ 无注入面 |
| 限流原子递增 | `TokenCacheService.java:208-216` Lua 脚本 `CHECK_AND_INCREMENT_LOGIN_SCRIPT` | ✓ |
| disabled 用户写 token 竞态防护 | `TokenCacheService.java:222-227` `batchStoreTokens` Lua 原子「读状态→仅 active 才写」 | ✓ |

### 🟠 补查发现的问题

**S1.【MEDIUM·配置】生产 Cookie 不带 Secure 标志**
- 证据：`JwtProperties.java:12-17` `boolean cookieSecure`（原始类型，未配置即默认 false）；`CookieTokenManager.java:62,72` `setSecure(jwtProperties.cookieSecure())`；`application-stable.yml:63-66` 与 `application-dev.yml:71-74` 的 `app.jwt` 块**均未设 `cookie-secure`**。
- 后果：所有 profile（含 stable 生产）Cookie 缺 Secure → 存在 HTTP 降级/MITAM 时 token 可被截获。
- 修复：`application-stable.yml`（及 evaluation）补 `cookie-secure: true`。建议进一步像 JWT 密钥那样在非 dev 强制 true（fail-fast）。
- 类型：安全 / 配置

**S2.【LOW】`validateToken` 不校验 `type` claim，由调用方各自检查（脆弱）**
- 证据：`JwtTokenProvider.java:83-90` 只验签名+issuer+过期；type 校验散落在 `JwtAuthenticationFilter.java:62`（"access"）和 `AuthServiceImpl.java:199`（"refresh"）。
- 风险：新增调用方若忘记查 type，可能用 refresh token 当 access 用。当前两处调用都查了，不构成线上 bug。
- 类型：健壮性（设计）

**S3.【LOW】清除 Cookie 时未设 Secure**
- 证据：`CookieTokenManager.java:79-86` `buildExpiredCookie` 只设 HttpOnly/Path/MaxAge=0/SameSite，无 `setSecure`。
- 影响：清空操作（空值+立即过期）影响轻微。
- 类型：一致性

---

## 🔺 严重度裁定（主审 vs agent 分歧，附证据）

| 项 | agent | 主审 | **裁定** | 依据 |
|---|---|---|---|---|
| register 裸 `RuntimeException` | CRITICAL | MEDIUM | **MEDIUM** | `GlobalExceptionHandler.java:100-101` 有 `@ExceptionHandler(Exception.class)` 兜底 → 返回统一格式，**非裸 500**。丢 ErrorCode 但不崩溃；触发条件（默认 USER 角色缺失）是部署级 misconfig。 |
| `UserStatus.fromCode` 抛 IAE | CRITICAL | LOW | **LOW** | agent 自承「无调用方触发」；且 `GlobalExceptionHandler.java:74-75` 有 IAE 专属 handler，即便触发也被优雅处理。属死代码里的潜在违规 → 删 `fromCode` 即可。 |
| `UserController` 入参裸 Integer | HIGH | LOW | **MEDIUM** | 功能安全（`SysUserServiceImpl.java:79` `isValid` 兜底），但 spec 要求入口枚举校验。 |

---

## ⚠️ 纠偏：agent 的 H5 是 FALSE POSITIVE（勿改）

agent 报「`assignPermissions` 未驱逐用户权限缓存」—— **错误**。源码 `SysRoleServiceImpl.java`：
```
119  transactionTemplate.executeWithoutResult(status -> { ... delete + insert ... });  // 事务
135  List<Long> userIds = userRoleMapper.selectUserIdsByRoleId(roleId);
136  for (Long userId : userIds) {
137      tokenCacheService.evictUserPermissions(userId);   // ← 提交后驱逐，存在
138  }
```
**L135-138 明明有驱逐**（事务提交后，顺序正确）。agent 漏读，将其列为 Top 3 #3 —— 不要据此改代码。两路交叉验证的价值正在于此。

---

## 🔴 HIGH（确定性，两路或主审单路有据）

### H1. `deleteRole` 多表删除无事务
- 证据：`service/impl/SysRoleServiceImpl.java:87-101`（写入 98-100：`rolePermissionMapper.deleteByRoleId` → `userRoleMapper.deleteByRoleId` → `roleMapper.deleteById`）
- 对比：同文件 `assignPermissions:119` 用了 `transactionTemplate.executeWithoutResult`。
- 后果：中途失败留下脏数据（绑定清空但角色未删，或反之）。
- 类型：数据完整性 / 事务边界

### H2. Role/Permission Entity 直返前端（spec 硬违规）
- 证据：`controller/RoleController.java:36`（`List<SysRole>`）、`:46`/`:51`（`SysRole`）、`:62`（`List<SysPermission>`）、`:78`（`List<SysPermission>`）；`dto/RoleDetailVO.java:11-14` 内嵌 `SysRole`+`List<SysPermission>`；`service/SysRoleService.java:10,14,16,22` 接口签名同样返回实体。
- 违规：`quality-guidelines.md` Forbidden Patterns「返回 Entity 给前端 → DTO 转换」。`SysRole`/`SysPermission` 的 `deleted`/`status`/`created_at`/`updated_at`/`parent_id`/`resource_type` 泄漏。
- 反证系疏漏：用户侧已有 `UserVO`（`dto/UserVO.java`，注释「不含 password」）做隔离，角色侧缺失。
- 类型：规范 / 信息泄漏

### H3. 禁用角色的权限仍生效 + 角色名仍进 JWT（latent 授权漏洞）
- 证据链：
  - `mapper/SysUserRoleMapper.xml:6-8` `selectRoleIdsByUserId`：不 join sys_role，不过滤 role status/deleted
  - `mapper/SysRoleMapper.xml:14-19` `selectByIds`：`WHERE deleted = 0 AND id IN (...)`，**无 `status = 1`**
  - `mapper/SysRolePermissionMapper.xml:6-14` `selectPermissionsByRoleIds`：只过滤 `p.status = 1 AND p.deleted = 0`（权限侧），**无 join sys_role / 无 `r.status`**
  - 消费方：`AuthServiceImpl.java:115-116,121,334-343`（角色名进 JWT）、`DatabaseUserPermissionProvider.java:32,39`（加载权限）
- 当前不构成线上漏洞（务必说清）：`SysRoleService` 无「禁用角色」接口（`updateRole:76-84` 只改 `roleDesc`），role.status 现实恒为 1。但解析逻辑**不一致**（过滤了 permission.status 却漏 role.status），一旦加禁用功能或改库即静默失效。
- 类型：安全 / 正确性（latent）
- 建议：三查询都 join `sys_role` 加 `r.status = 1 AND r.deleted = 0`。

---

## 🟠 MEDIUM

| # | 位置 | 问题 | 类型 |
|---|------|------|------|
| M1 | `AuthServiceImpl.java:170-171,183-185` | register 裸 `RuntimeException`（默认角色缺失 / 注册失败）→ 应 `ServiceException` | 规范/错误处理 |
| M2 | `AuthServiceImpl.java:282-287` | changePassword DB→Redis 跨存储，Redis 失败则旧 token 仍有效（改密未强制下线） | 一致性 |
| M3 | `SysRoleServiceImpl.java:93-96` | `deleteRole` 在删除**前**驱逐缓存，重填窗口会缓存旧权限；应提交后驱逐（对比 `assignPermissions:135-138`） | 并发/一致性 |
| M4 | `SysPermissionServiceImpl.java:47-54` | `deletePermission` 不删 `sys_role_permission` 引用、不驱逐缓存；且当前无 API 入口（死代码+潜在授权漏洞） | 安全/latent |
| M5 | `AuthController.java:41,58` | `getRemoteAddr()` 无 `forward-headers`，反代后限流 key 坍缩成代理 IP（限流失效/误杀） | 安全/运维 |
| M6 | `AuthServiceImpl.java:144-191` | register 无独立限流（login:92 有），仅靠 captcha 间接兜底 | 安全/防御纵深 |
| M7 | `AuthServiceImpl.java:92→98` | 限流计数在验证码校验**前**递增，错误验证码也烧次数，可被用来锁死受害者 IP（轻量 DoS） | 安全/逻辑 |
| M8 | `AuthServiceImpl.java:92-95,102,106` | 登录失败/限流未按 logging-guidelines 记 WARN，无法审计暴力破解（注：filter 层 JWT 失败有 WARN，`JwtAuthenticationFilter.java:108`） | 规范/可观测 |
| M9 | `AssignRolesRequest.java:9` / `AssignPermissionsRequest.java:9` | `@NotEmpty` + 全量覆盖语义 → 管理员**无法清空**某用户全部角色/权限。需产品确认语义 | 逻辑/产品 |
| M10 | `UserController.java:44` / `UserStatusUpdateResult.java:7` | 入参 + 响应均裸 `Integer status`，spec 要求入口枚举校验 | 规范 |
| M11 | `UserStatus.java:18-23` | `fromCode` 抛 `IllegalArgumentException`（spec 禁），且无调用方（死代码）→ 删除或改 ClientException | 规范 |

---

## 🟡 LOW

| # | 位置 | 问题 |
|---|------|------|
| L1 | `JwtTokenProvider.java:83-90` | `validateToken` 不校验 `type`，由调用方各自检查（S2） |
| L2 | `CookieTokenManager.java:79-86` | 清除 cookie 未设 `Secure`（S3） |
| L3 | `AuthServiceImpl.java:170` | 硬编码角色名 `"USER"`（魔 法串，重命名即触发 M1） |
| L4 | `UserUpdateRequest.java:11` | `avatar` 仅 `@Size(max=255)` 无 scheme 校验，可存 `javascript:` URI（存储型 XSS 面） |
| L5 | `AuthController.java:68-77,109-114` | refresh 允许从 body 取 token，削弱 HttpOnly cookie 语义 |
| L6 | `ChangePasswordRequest.java:8` / `RegisterRequest.java:9` vs `AuthServiceImpl.java:360-371` | DTO 只 `@Size(min=8)` 无 `max=72`；且提示「至少8位」与实际「3 类字符」规则不符，误导用户 |
| L7 | `RegisterRequest.java:11` / `AuthServiceImpl.java:166` | `nickname` 可空无 `@NotBlank`，空串不回退用户名 |
| L8 | `SysRoleServiceImpl.java:93-96,135-138` | `for(userId) evictUserPermissions` 逐用户 Redis 往返，建议批量 |
| L9 | `SysUserServiceImpl.java:56-62` | `listUsers` 手动 `eq(deleted,0)` 与 `@TableLogic`（`SysUser.java:21`）重复 |
| L10 | `SysUserServiceImpl.java:107-112` / `SysRoleServiceImpl.java:112-117` | 角色/权限存在性校验在事务外（TOCTOU，DB 外键兜底） |
| L11 | `SysUserServiceImpl.java:89,91,95` | `status == UserStatus.x.code` Integer 拆箱（isValid 已防 null，脆弱写法） |
| L12 | `RoleController.java:70-74` | `assignPermissions` 返回手搓 `Map<String,Object>`，与模块内 record DTO 风格不一致 |
| L13 | `UserController.java:38,53` / `RoleController.java:50,55` | `POST /{id}/update`、`POST /{id}/delete` 非 RESTful（PUT/DELETE） |
| L14 | `SysUserMapper.xml:11` | `selectActiveById`（getCurrentUser/updateProfile 用）SELECT 含 `password` 列，读出即弃 |
| L15 | `SysUser.java:8` vs `SysRole.java:8` | 主键策略不一致（INPUT 雪花 vs AUTO），疑为有意，仅记录 |

---

## ✅ 做得好的地方（balance）

- **防用户枚举**：login 对「用户不存在/密码错/已禁用」统一抛 `LOGIN_FAILED`（`AuthServiceImpl.java:102,106,111`）
- **密码**：BCrypt + 复杂度（8-72、禁空白、3+ 类别，`AuthServiceImpl.java:360-371`）
- **Token 轮换**：refresh 走 `rotateRefreshToken`（`AuthServiceImpl.java:203`）
- **fire-and-forget 线程池合规**：`AuthAsyncConfig.java:34-44` 显式 `ThreadPoolExecutor`+有界队列+daemon+饱和丢弃+`destroyMethod=shutdown`
- **事务**：全模块用 `TransactionTemplate`，无 `@Transactional`
- **构造器注入**：无 `@Autowired` 字段注入
- **@PreAuthorize 类级保护**：`UserController.java:16` / `RoleController.java:24`
- **assignRoles/assignPermissions**：正确包事务 + 提交后驱逐缓存（后者即 agent 误判之处）

---

## 📋 文件 × 问题数 汇总（合并去重 + 纠偏后）

| 文件 | HIGH | MED | LOW |
|------|------|-----|-----|
| `service/impl/SysRoleServiceImpl.java` | H1 | M3 | L8, L10 |
| `controller/RoleController.java` | H2 | — | L12, L13 |
| `dto/RoleDetailVO.java` | H2 | — | — |
| `service/SysRoleService.java` | H2 | — | — |
| `service/impl/AuthServiceImpl.java` | H3 | M1,M2,M6,M7,M8 | L3, L6, L7 |
| `service/impl/DatabaseUserPermissionProvider.java` | H3 | — | — |
| `service/impl/SysPermissionServiceImpl.java` | — | M4 | — |
| `controller/AuthController.java` | — | M5, M6 | L5 |
| `controller/UserController.java` | — | M10 | L13 |
| `enums/UserStatus.java` | — | M11 | — |
| `dto/AssignRolesRequest.java` / `AssignPermissionsRequest.java` | — | M9 | — |
| `dto/ChangePasswordRequest.java` / `RegisterRequest.java` | — | — | L6, L7 |
| `dto/UserUpdateRequest.java` | — | — | L4 |
| `dto/UserStatusUpdateResult.java` | — | M10 | — |
| `service/impl/SysUserServiceImpl.java` | — | — | L9, L10, L11 |
| `infrastructure` 补查：`JwtProperties`/`CookieTokenManager`/config | — | S1 | L1, L2 |
| 5 个 mapper XML | H3 | — | L14 |
| entity / 其余 DTO / `AuthAsyncConfig` / `JwtTokenProvider`(除L1) / `JwtAuthenticationFilter` | — | — | — |

---

## 🔎 仍未覆盖（本轮补查后剩余）

以下超出 `user/` 模块 + 本次补查范围，如需可继续：
- `TokenCacheService` 内部实现细节：refresh token reuse detection（轮换是否一次性、旧 token 是否即失效）、权限缓存 key/TTL/序列化
- `CaptchaService`：验证码存储介质、一次性消费、TTL
- `UserDetailsService`/权限源链路完整性

---

*本报告由两路并行审查交叉比对生成；agent 的 1 处假阳性（assignPermissions 未驱逐缓存）已纠正。*
