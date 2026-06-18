# PRD：登录路径 review 修复

## 背景

对 `CaptchaService` / `AuthServiceImpl` / `TokenCacheService`（当前未提交改动）做 code review，发现 5 个待修项。本任务落地修复。除明确说明外，**无对外行为变更**——限流、登录、刷新、登出语义保持不变。

## 范围（5 项）

### 1. 权限预热改用专用线程池 — `AuthServiceImpl.java:110`
- **现状**：`CompletableFuture.runAsync(() -> userPermissionProvider.loadUserPermissions(userId))` 跑在 common ForkJoinPool，异常被静默吞掉，且阻塞式 Redis/DB 调用会占用全局并行度。
- **不用结构化并发**：项目 `infrastructure/concurrent/`（`ScopedTasks`/`TaskScope`）是 fork-join 框架，`join()`/`close()` 阻塞到子任务完成，且禁止子任务逃逸出 scope。fire-and-forget 预热不适合（join 会重新阻塞登录响应，不 join 则违反不变量）。
- **方案**：新增 `authPermissionWarmupExecutor` Bean，**显式 `ThreadPoolExecutor`**（满足硬性要求）：
  - `corePoolSize = ThreadPoolConstants.lightCore()`、`maxPoolSize = lightMax()`、`keepAlive = 60s`
  - 队列 `new LinkedBlockingQueue<Runnable>(64)`（有界）
  - `ThreadFactory`：名 `auth-perm-warmup-%d`，`daemon = true`
  - `RejectedExecutionHandler`：自定义，饱和时 DEBUG 参数化日志后丢弃（best-effort；cache miss 由 `getCurrentUser` 同步兜底，绝不能回压登录线程）
  - `@Bean(destroyMethod = "shutdown")` 管理生命周期
- `AuthServiceImpl` 注入该 executor 替换 `runAsync`；任务体 `try/catch` 失败记 **WARN**（参数化，不记 token 原文，遵循 logging-guidelines）。

### 2. 命名 / Javadoc 修正
- `TokenCacheService.checkAndIncrementLoginAttempts` 的 Javadoc「剩余可用次数」不准确 → 改为「递增后的计数（1..limit），-1 表示已超限」。
- `AuthServiceImpl.login` 局部变量 `remaining` → `attemptCount`。

### 3. 死代码清理 — `TokenCacheService`
全仓复核确认生产无引用，仅自指测试引用。删除：
- `getUserIdByRefreshToken`（零引用）
- `isLoginRateLimited` / `incrementLoginAttempts` / `getRemainingLoginAttempts`
- `INCR_WITH_EXPIRE_SCRIPT`（仅被 `incrementLoginAttempts` 使用）
- 同步删除 `TokenCacheServiceTest` 对应 6 个用例
- ⚠️ `ChunkUploadServiceImpl.java:469` 的同名 `INCR_WITH_EXPIRE_SCRIPT` 是独立副本，**不动**

### 4. logout 语义清晰化（全端下线，已确认）
- 保持 `revokeAllTokens` 全端下线行为不变（符合 jwt-best-practices §6「服务端黑名单 + Refresh 删除」）。
- 去掉未使用的 `accessToken` 形参（`AuthService` 接口 + `AuthServiceImpl` + `AuthController:85`）。
- 补 Javadoc：明确「注销 = 撤销该用户全部会话/设备」。

### 5. refreshToken 走 Pipeline
- `TokenCacheService` 新增 `batchStoreRefreshTokens(userId, tokenId, roles, refreshToken, accessExp, refreshExp)`：status GET + 存 access + 存 refresh 合并为 1 个 Pipeline（对齐现有 `batchStoreLoginTokens` 结构）。
- `AuthServiceImpl.refreshToken` 改走它，删除单独的 `getUserStatus` + `storeAccessToken` + `storeRefreshToken` 调用；超限状态仍抛 `USER_DISABLED`。

## 非目标
- CaptchaService 的 low 级记录项（`checkRateLimit` 非分布式、`POOL_MAX=200` 死容量、`removeIf` O(n)）本次不动。
- `batchStoreLoginTokens` 的 orphan-token 边角（review 已论证不可利用）不处理。

## 验收
- 编译通过；`AuthServiceTest` / `TokenCacheServiceTest` / `SysUserServiceTest` 通过。
- `gitnexus_detect_changes` 仅命中预期符号。
- 无行为回归：登录限流边界（10 次/5 分钟）、登录/刷新/登出语义不变。
- 改前按 CLAUDE.md 对每个被改符号跑 `gitnexus_impact`。
