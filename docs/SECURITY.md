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
| `team:view` | ✅ | ✅ |
| `team:manage` | ✅ | ❌ |

## 团队权限模型

### 角色层级

团队内采用枚举值分层，值越大权限越高，便于代码中直接比较：

| 角色 | 枚举值 | 权限范围 |
|------|--------|----------|
| `CREATOR` | 30 | 解散团队、设置创建者额度、转让、修改任何成员角色 |
| `ADMIN` | 20 | 邀请/移除普通成员、审批上传、设置成员额度 |
| `MEMBER` | 10 | 上传文档（需审批）、查看团队信息 |

角色比较规则：`CREATOR > ADMIN > MEMBER`，代码中直接通过 `role.getCode() >= requiredRole.getCode()` 判断。

### 角色迁移约束

- 仅 CREATOR 可变更其他成员角色（提升/降低）
- CREATOR 角色不可被变更（暂不支持转让）
- 成员降级后其待审批文档仍保留，不影响已有审批流程

## 文档权限校验

统一权限校验门面 `DocumentOwnershipChecker`，位于 `team/security` 包，跨 `team` 和 `rag` 模块使用。

```
操作请求 → DocumentOwnershipChecker
              ├── 个人文档（ownerId = 当前用户）→ 直接放行
              └── 团队文档 → 查成员角色 + 角色匹配
                   ├── 查看 → 任意团队成员均可
                   ├── 下载 → 任意团队成员均可
                   ├── 删除 → ADMIN / CREATOR
                   └── 修改 → ADMIN / CREATOR
```

- 个人文档仅 owner 本人可操作
- 团队文档需先验证当前用户是团队成员，再校验角色是否满足操作要求
- 校验失败抛出 `AccessDeniedException`，由全局异常处理器统一返回 403

## 上传权限路由

```
UploadStrategy（接口）
├── PersonalUploadStrategy
│   └── teamId = null → 直接上传 + 触发 ETL
└── TeamUploadStrategy
    └── teamId ≠ null
        ├── ADMIN / CREATOR → 状态 PROCESSING → 直接触发 ETL
        └── MEMBER → 状态 PENDING_APPROVAL → 等待管理员审批
```

- 策略选择通过 `UploadStrategyFactory` 完成，根据请求中 `teamId` 是否为 null 路由到对应实现
- 团队上传前校验成员个人额度（按 team + user 汇总已用 MB）

## 审批流权限

| 操作 | CREATOR | ADMIN | MEMBER |
|------|---------|-------|--------|
| 查看 pending 列表 | ✅ | ✅ | ❌ |
| 审批通过/拒绝 | ✅ | ✅ | ❌ |
| 查看我的审批记录 | ✅ | ✅ | ✅ |

- 成员只能查看自己提交的审批单状态
- 管理员和创建者可以查看团队待审批列表并执行审批操作
- 审批通过后文档状态变为 `PROCESSING`，自动触发 ETL 入库

## 并发安全措施

| 场景 | 机制 | 说明 |
|------|------|------|
| 解散团队 | `SELECT ... FOR UPDATE` 行锁 | 先锁定团队行，防并发解散导致数据不一致 |
| 添加成员 | 事务 + `DuplicateKey` 兜底 | 唯一约束防重复加入；异常捕获后转友好提示 |
| 审批 review | 事务内查状态 | `SELECT` 后立即校验状态机，防并发审批同一文档 |

## Spring Security 权限注解

团队 Controller 方法使用 `@PreAuthorize` 声明式鉴权：

```java
@PreAuthorize("hasAuthority('team:manage')")
@PostMapping("/{userId}")
public GlobalResponse<TeamMemberVO> addMember(...) { ... }
```

Service 层通过 `SecurityUtils.getCurrentUserId()` 获取当前用户 ID，避免从 Controller 参数手动传递，防止越权：

```java
Long currentUserId = SecurityUtils.getCurrentUserId();
TeamMember member = teamMemberMapper.selectByTeamAndUser(teamId, currentUserId);
```

## 团队相关 RBAC 权限种子

数据库初始化时插入以下权限码，绑定到对应角色：

| 权限码 | 说明 | 默认绑定角色 |
|--------|------|-------------|
| `team:view` | 查看团队信息 | ADMIN |
| `team:manage` | 管理团队（邀请/移除成员、审批文档） | ADMIN |

- `team:manage` 默认不包含解散权限——解散操作在业务层单独校验 CREATOR 身份
- 未来扩展 `team:delete` 等权限码时，按最小权限原则逐项拆分
