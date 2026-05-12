# 安全设计

> 认证、验证码、权限模型的详细设计。

## 双 Token 认证

```
登录 → Access Token (15min) + Refresh Token (24h)
      ↓
请求 → JwtAuthenticationFilter
      ↓ JWT 验证 + Redis 吊销检查 + 用户状态检查
      ↓ 权限加载（Redis 缓存 300s → DB fallback）
      ↓
@PreAuthorize("hasAuthority('chat:send')")
```

- JWT 载荷：`sub` (userId), `jti` (UUID), `roles`, `type`, `iss`
- 权限从 Redis/DB 动态查询，支持实时变更
- 改密/禁用/删除用户时自动吊销所有 Token

## 滑块验证码

```
GET /api/auth/captcha → {captchaId, backgroundImage, puzzleImage, answer(dev only)}
                                    ↓
前端拖动拼图块 → POST /api/auth/login 携带 captchaId + captchaCode
                                    ↓
后端校验：Caffeine 缓存取答案，±5px 容差，一次性使用
```

- **纯 Java 2D 实现**，无外部图片/模型依赖
- dev 环境返回 answer 坐标，方便 API 测试；其他环境不返回

## RBAC 权限模型

```
sys_user ─< sys_user_role >─ sys_role ─< sys_role_permission >─ sys_permission
```

| 权限码 | ADMIN | USER |
|--------|-------|------|
| `chat:send` | ✅ | ✅ |
| `chat:stream` | ✅ | ✅ |
| `conversation:manage` | ✅ | ✅ |
| `usage:view` | ✅ | ✅ |
| `model:config` | ✅ | ❌ |
| `prompt:manage` | ✅ | ❌ |
| `user:manage` | ✅ | ❌ |
| `role:manage` | ✅ | ❌ |
