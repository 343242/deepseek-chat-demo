# JWT 最佳实践指导文档

> 基于 JJWT 官方推荐文章、OWASP Authentication Cheat Sheet、OAuth 2.0 Security BCP (RFC 9700)、jwt.io 官方文档、RandomKeygen JWT Security Guide、MojoAuth 2026 Auth Security Report 综合整理。

---

## 1. Token 结构与签名

### 1.1 算法选择

| 场景 | 推荐算法 | 说明 |
|------|---------|------|
| 同一服务签名+验证 | HS256（HMAC-SHA256） | 对称加密，密钥 ≥ 256 位 |
| 微服务/多服务验证 | ES256（ECDSA P-256） | 非对称，性能优于 RS256，同等安全性 |
| 遗留系统/合规要求 | RS256（RSA-SHA256） | 非对称，密钥 ≥ 2048 位 |

**关键规则：**
- **永远显式指定算法**，不接受 token header 中的 `alg` 字段。防止算法混淆攻击（`alg=none`、RS256→HS256 切换）。— *RandomKeygen JWT Security Guide*
- JJWT 0.12+ 使用 `Jwts.builder().signWith(key)` 会自动根据 key 类型选择算法，验证时 `verifyWith(key)` 同理，这已经安全。但如果用字符串密钥，确保通过 `Keys.hmacShaKeyFor()` 转换。— *JJWT 官方文档*

### 1.2 密钥管理

- HMAC 密钥长度 ≥ 算法输出长度（HS256 ≥ 256 位 / 32 字节）。— *RFC 7518*
- 使用 CSPRNG（加密安全随机数生成器）生成密钥：`openssl rand -base64 32`。— *MojoAuth Security Report*
- **每环境独立密钥**（dev/staging/prod），不共享。— *RandomKeygen*
- **禁止**在源码中硬编码密钥。通过环境变量或密钥管理服务（Vault、KMS）注入。— *OWASP*
- 生产环境推荐使用非对称算法（ES256/RS256），API 服务只持有公钥验证，私钥仅在授权服务上。— *MojoAuth Security Report*
- 定期轮换密钥（建议每 90 天或更短）。

### 1.3 Claims 设计

**必须包含的 Registered Claims：**

| Claim | 用途 | 必须 |
|-------|------|------|
| `sub` (subject) | 用户唯一标识 | ✅ |
| `iss` (issuer) | 签发者标识 | ✅ |
| `exp` (expiration) | 过期时间 | ✅ |
| `iat` (issued at) | 签发时间 | ✅ |
| `jti` (JWT ID) | 唯一标识，用于吊销 | ✅ |
| `aud` (audience) | 接收方标识 | 推荐多服务场景 |

**自定义 Claims 规则：**
- 不放敏感信息（密码、手机号等），JWT payload 是 Base64 编码，**任何人可解码**。— *jwt.io Introduction*
- 尽量精简，JWT 过大会导致 HTTP Header 超限（部分服务器限制 8KB）。— *jwt.io*
- 自定义 claim 命名使用 URI 格式或注册到 IANA JWT Registry 避免冲突。— *RFC 7519 §4.2*

---

## 2. Token 存储策略

### 2.1 Cookie vs Web Storage 对比

| 存储方式 | XSS 防护 | CSRF 防护 | 持久性 | 推荐度 |
|---------|---------|---------|-------|-------|
| **HttpOnly Cookie** | ✅ JS 不可读 | ⚠️ 需额外防护 | 浏览器控制 | ⭐⭐⭐⭐⭐ |
| 内存 (JS 变量) | ✅ | ✅ | 页面刷新丢失 | ⭐⭐⭐⭐ |
| sessionStorage | ⚠️ XSS 可读 | ✅ | 标签页关闭清除 | ⭐⭐⭐ |
| localStorage | ❌ XSS 可读 | ✅ | 永久 | ⭐（不推荐） |

**结论：Access Token 和 Refresh Token 均应存储在 HttpOnly Cookie 中。** — *MojoAuth 2026 Security Report, OWASP HTML5 Security Cheat Sheet*

### 2.2 Cookie 安全属性

```java
// 推荐配置
Cookie cookie = new Cookie("access_token", token);
cookie.setHttpOnly(true);   // 防止 JavaScript 读取
cookie.setSecure(true);     // 仅 HTTPS 传输
cookie.setPath("/api");     // 限制作用域
cookie.setMaxAge(900);      // 15 分钟（与 token 过期同步）

// Spring Boot 6+ / Servlet 5.0+
cookie.setAttribute("SameSite", "Strict");  // 或 "Lax"
```

- `SameSite=Strict`：完全阻止跨站请求携带 Cookie（最安全，但可能影响从外部链接进入的场景）。
- `SameSite=Lax`：GET 导航请求允许携带，POST/iframe/ajax 不携带（推荐平衡方案）。— *MojoAuth*

### 2.3 XSS vs CSRF 防护组合

**Cookie + CSRF Token 方案：**
- HttpOnly Cookie 防 XSS → 但引入 CSRF 风险
- SameSite=Strict/Lax 防 CSRF → 覆盖大部分场景
- 关键写操作额外验证 CSRF Token 或 Origin/Referer Header → 纵深防御

**双重提交 Cookie 模式（适合 SPA）：**
- 服务端设置 HttpOnly 的 auth cookie
- 登录时同时在非 HttpOnly cookie 或 response body 中放一个 CSRF token
- 前端在写请求的 Header 中带上 CSRF token，服务端比对

— *MojoAuth Security Report, OWASP CSRF Prevention Cheat Sheet*

---

## 3. Token 生命周期管理

### 3.1 Access Token vs Refresh Token

| | Access Token | Refresh Token |
|---|---|---|
| **有效期** | 15 分钟 ~ 1 小时 | 7 ~ 30 天 |
| **用途** | API 请求鉴权 | 换取新的 Access Token |
| **存储** | HttpOnly Cookie | HttpOnly Cookie（独立路径） |
| **包含信息** | userId, roles, type | userId, type |
| **是否可吊销** | 通过 Redis 黑名单 | 通过 Redis/DB 删除 |

### 3.2 过期策略

- Access Token：**15 分钟**是最佳实践上限。高风险系统建议 5 分钟。— *RandomKeygen*
- Refresh Token：7~30 天，结合 Rotation 机制。— *OAuth 2.0 BCP (RFC 9700)*
- 过期时间从 `iat`（签发时间）计算，不用 `nbf`。需考虑时钟偏差（建议 30s tolerance）。— *jwt.io*

### 3.3 Refresh Token Rotation（刷新时轮换）

每次使用 Refresh Token 换取新 Access Token 时：
1. **签发新的 Refresh Token**
2. **立即作废旧 Refresh Token**
3. 检测旧 Token 被复用 → 吊销整个 Token Family → 强制重新登录

```
Token Family 示例：
RT1 → (使用) → RT2, RT1 作废
RT2 → (使用) → RT3, RT2 作废
RT2 → (再次使用，检测到复用) → 吊销 RT3, 强制重新登录
```

— *OAuth 2.0 Security BCP (RFC 9700), MojoAuth*

### 3.4 Token 吊销策略

JWT 本身是无状态的，无法直接吊销。推荐方案：

| 方案 | 实现方式 | 适用场景 |
|------|---------|---------|
| **短过期 + Redis 黑名单** | 登出时将 `jti` 存入 Redis（TTL = token 剩余过期时间） | 大多数场景 |
| **Token 版本号** | 用户表增加 `token_version`，验证时比对 | 需要全局吊销场景 |
| **Refresh Token 删除** | 删除 Redis/DB 中的 refresh token | 仅阻止续签 |
| **用户状态标记** | 验证 token 时查用户是否被禁用 | 高安全场景 |

**本项目推荐**：Redis 黑名单（`jti` + TTL）+ Refresh Token 服务端存储 + 用户状态检查。三层纵深。

---

## 4. 安全防护

### 4.1 防止 Token 泄露

- ✅ HttpOnly Cookie（JS 不可读）
- ✅ Secure 标志（仅 HTTPS）
- ✅ 服务日志中脱敏或排除 JWT
- ✅ 不在 URL 中传递 Token（会被日志、浏览器历史、Referer 泄露）
- ❌ 禁止 localStorage/sessionStorage 存储 Token

### 4.2 防止重放攻击

- 使用 `jti`（JWT ID）+ Redis 黑名单
- Access Token 短有效期（15 分钟）限制重放窗口
- Refresh Token Rotation 限制单次使用

### 4.3 防止算法混淆攻击

- **JJWT 0.12+ 已内置防护**：`signWith(SecretKey)` / `verifyWith(SecretKey)` 强制绑定 key 类型
- 验证时始终指定 `requireIssuer()` 等约束
- 不信任 token header 中的 `alg` 字段（JJWT 在 parser 层处理）

### 4.4 Rate Limiting

- 登录端点：10 次/15分钟/IP — *MojoAuth*
- 注册端点：5 次/小时/IP
- Token 刷新端点：20 次/分钟/用户
- 密码修改：5 次/小时/用户
- **使用滑动窗口**（非固定窗口）防止窗口边界突发。— *MojoAuth*
- 配合验证码（CAPTCHA）触发机制

---

## 5. 服务端实践

### 5.1 无状态 vs 有状态 Token

| | 纯无状态 | 有状态（本项目） |
|---|---|---|
| 验证方式 | 仅验签名+过期 | 签名+过期+Redis 黑名单+用户状态 |
| 吊销能力 | ❌ 无法即时吊销 | ✅ 即时吊销 |
| 性能 | 最优 | Redis 查询开销极小 |
| 安全性 | 中 | 高 |

**本项目选择有状态方案**：无状态 JWT 的最大缺陷是无法吊销，在 Web 应用中不可接受。

### 5.2 Redis 存储策略

```
# Token 黑名单
jwt:blacklist:{jti} → "1"  TTL = token 剩余过期时间

# Refresh Token 存储
jwt:refresh:{jti} → userId  TTL = refresh token 过期时间

# 用户权限缓存
jwt:permissions:{userId} → permissionList  TTL = access token 过期时间

# 登录限流
rate:login:{ip} → count  TTL = 15min
```

### 5.3 JWT ID (jti) 的使用

- 每个token 使用唯一的 `jti`（UUID v4）。— *RFC 7519*
- `jti` 用于吊销、审计追踪、防重放
- Refresh Token 的 `jti` 同时作为 Redis 存储的 key
- Access Token 的 `jti` 用于登出时加入黑名单

---

## 6. 常见反模式（不要做的事）

| ❌ 反模式 | ✅ 正确做法 |
|----------|-----------|
| localStorage 存 JWT | HttpOnly Cookie |
| Token 无过期时间 | Access Token 15min, Refresh Token 7~30d |
| Refresh Token 不轮换 | 每次刷新换新 Token + Family 检测 |
| 密钥 < 256 位 / 硬编码 | CSPRNG 生成 ≥ 256 位, 环境变量注入 |
| payload 放敏感信息 | JWT 是 Base64 不是加密 |
| 只验签名不验 issuer/audience | 验证全部 registered claims |
| 从 request body 读 userId | 从已验证的 JWT claims 中读取 |
| 登录返回不同错误消息（"用户不存在" vs "密码错误"） | 统一返回 "用户名或密码错误" |
| URL 参数传 Token | 使用 Header 或 Cookie |
| 只用 client-side 删除实现登出 | 服务端 Redis 黑名单 + Refresh Token 删除 |

---

## 7. 本项目合规检查清单

### ✅ 已合规项

- [x] JJWT 0.13.0，依赖 `jjwt-api` + `jjwt-impl`(runtime) + `jjwt-jackson`(runtime)
- [x] HMAC-SHA256 + ≥ 256 位密钥（`Keys.hmacShaKeyFor()`）
- [x] 启动时校验密钥强度（`@PostConstruct validateSecret()`）
- [x] 非开发环境拒绝已知默认密钥
- [x] Claims 包含 `sub`, `iss`, `iat`, `exp`, `jti`
- [x] 区分 Access Token（含 roles）和 Refresh Token
- [x] 验证时强制校验 `issuer`（`requireIssuer()`）
- [x] Redis 黑名单吊销（`jti` + TTL）
- [x] Refresh Token 服务端存储 + 可删除
- [x] Cookie 存储（`CookieTokenManager` SRP 抽取）
- [x] 登录端点 Rate Limiting（IP 限流）
- [x] 从 JWT claims 提取 userId（不从 request body）

### ⚠️ 可优化项

- [x] ~~Cookie 设置 `SameSite` 属性~~ → 已在 CookieTokenManager 中添加 `SameSite=Lax`
- [x] ~~Refresh Token Rotation~~ → 已实现（`tokenCacheService.rotateRefreshToken()` 原子删除旧 token + 签发新 token）
- [x] ~~登录错误消息统一~~ → 已统一返回 "用户名或密码错误"（AuthServiceImpl:89,93,98,104）
- [x] ~~密码修改后吊销所有 token~~ → 已实现（`tokenCacheService.revokeAllTokens(userId)`）
- [x] ~~Access Token 有效期 ≤ 15 分钟~~ → 已配置 `access-expiration: 900`（900秒 = 15分钟）
- [ ] `aud` (audience) claim：多服务场景推荐添加（当前单服务非必须）
- [ ] Refresh Token Family 检测：如果已被吊销的 refresh token 再次被使用，应吊销整个 family 并强制重新登录（当前仅返回 null，未检测复用攻击）

### 📋 参考来源

1. [jwt.io Introduction](https://jwt.io/introduction) — JWT 结构、Claims 分类、验证 vs 验证区别
2. [RandomKeygen JWT Security Best Practices](https://randomkeygen.com/guides/jwt-security) — 算法选择、密钥要求、验证清单
3. [MojoAuth: 12 Authentication API Security Mistakes (2026)](https://mojoauth.com/blog/authentication-api-security-mistakes) — 存储策略、Rotation、Rate Limiting、BOLA
4. [OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html) — 用户枚举、错误消息统一
5. [OAuth 2.0 Security BCP (RFC 9700)](https://www.rfc-editor.org/rfc/rfc9700) — Refresh Token Rotation
6. JJWT Official (GitHub: jwtk/jjwt) — API 用法、0.12+ 最佳实践

---

*文档创建时间：2026-05-09 | JJWT 版本：0.13.0*
