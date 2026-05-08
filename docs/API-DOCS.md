# API 接口文档

> Base URL: `http://localhost:8080`
>
> 认证方式：Token 写入 HttpOnly Cookie (`access_token`)，也可通过 `Authorization: Bearer <token>` 传递

---

## 目录

- [认证](#认证)
- [聊天](#聊天)
- [对话管理](#对话管理)
- [系统提示词](#系统提示词)
- [模型参数](#模型参数)
- [用量统计](#用量统计)
- [用户管理](#用户管理)
- [角色权限](#角色权限)
- [通用错误](#通用错误)

---

## 认证

### GET /api/auth/captcha

获取滑块验证码。公开接口。

**Response：**

```json
{
  "captchaId": "7781671d05474f99b43f864f447c3b7c",
  "backgroundImage": "data:image/png;base64,...",
  "puzzleImage": "data:image/png;base64,...",
  "answer": 137
}
```

> `answer` 仅 dev 环境返回，用于测试。生产环境为 `null`。

---

### POST /api/auth/register

用户注册。公开接口。

**Request：**

```json
{
  "username": "alice",
  "password": "Abc12345!",
  "email": "alice@example.com",
  "nickname": "Alice",
  "captchaId": "uuid",
  "captchaCode": "182"
}
```

| 字段 | 必填 | 规则 |
|------|------|------|
| username | ✅ | 非空 |
| password | ✅ | 8~72 位，至少包含大写、小写、数字、特殊字符中的 3 种 |
| email | ✅ | 合法邮箱格式 |
| nickname | | 最多 50 字符，默认使用 username |
| captchaId | ✅ | 从 /captcha 获取 |
| captchaCode | ✅ | 滑块 x 坐标 |

**Response：** 用户信息

```json
{
  "id": 123,
  "username": "alice",
  "nickname": "Alice",
  "email": "alice@example.com",
  "avatar": null,
  "roles": ["USER"]
}
```

---

### POST /api/auth/login

登录。公开接口。Token 写入 HttpOnly Cookie。

**Request：**

```json
{
  "username": "admin",
  "password": "admin123",
  "captchaId": "uuid",
  "captchaCode": "182"
}
```

**Response：**

```json
{
  "user": {
    "id": 1,
    "username": "admin",
    "nickname": "系统管理员",
    "email": null,
    "avatar": null,
    "roles": ["ADMIN"]
  }
}
```

**Cookie：**

| 名称 | 属性 | Path | Max-Age |
|------|------|------|---------|
| `access_token` | HttpOnly | `/api` | 900s (15min) |
| `refresh_token` | HttpOnly | `/api/auth/refresh` | 86400s (24h) |

---

### POST /api/auth/refresh

刷新 Token。公开接口。支持从请求体或 Cookie 获取 refresh_token。

**Request（可选，不传则从 Cookie 读取）：**

```json
{
  "refreshToken": "eyJhbG..."
}
```

**Response：** 用户信息 + 新 Token 写入 Cookie（同 login）

---

### POST /api/auth/logout

登出，吊销所有 Token 并清除 Cookie。需要登录。

**Response：**

```json
{ "message": "已登出" }
```

---

### GET /api/auth/me

获取当前用户信息。需要登录。

**Response：**

```json
{
  "id": 1,
  "username": "admin",
  "nickname": "系统管理员",
  "email": null,
  "avatar": null,
  "roles": ["ADMIN"]
}
```

---

### PATCH /api/auth/me/password

修改当前用户密码。需要登录。

**Request：**

```json
{
  "oldPassword": "OldPass123!",
  "newPassword": "NewPass456!"
}
```

> 新密码同样需满足复杂度规则。

**Response：**

```json
{ "message": "密码已修改" }
```

---

### PATCH /api/auth/me/profile

修改当前用户资料。需要登录。

**Request：**

```json
{
  "nickname": "新昵称",
  "email": "new@example.com",
  "phone": "13800138000",
  "avatar": "https://example.com/avatar.png"
}
```

| 字段 | 规则 |
|------|------|
| nickname | 最多 50 字符 |
| email | 合法邮箱格式，最多 100 字符 |
| phone | 11 位手机号（1 开头） |
| avatar | URL，最多 255 字符 |

**Response：** 更新后的用户信息

---

## 聊天

### GET /api/models

获取可用模型列表（多厂商聚合）。

**权限：** `chat:send`

**Response：**

```json
[
  {
    "id": "deepseek-v4-flash",
    "providerId": "deepseek",
    "providerName": "DeepSeek",
    "compositeId": "deepseek/deepseek-v4-flash",
    "ownedBy": "deepseek",
    "created": 1773799200
  },
  {
    "id": "glm-5.1",
    "providerId": "zhipu",
    "providerName": "智谱 AI",
    "compositeId": "zhipu/glm-5.1",
    "ownedBy": "zhipuai",
    "created": 0
  },
  {
    "id": "MiniMax-M2.1",
    "providerId": "minimax",
    "providerName": "MiniMax",
    "compositeId": "minimax/MiniMax-M2.1",
    "ownedBy": "minimax",
    "created": 0
  }
]
```

| 字段 | 说明 |
|------|------|
| id | 模型 ID（厂商返回的原始 ID） |
| providerId | 厂商标识（`deepseek` / `zhipu` / `minimax`） |
| providerName | 厂商显示名称 |
| compositeId | 复合 ID，用于 API 请求时精确路由到指定厂商 |

---

### POST /api/chat

阻塞式聊天。

**权限：** `chat:send`

**Request：**

```json
{
  "model": "deepseek/deepseek-v4-flash",
  "message": "你好",
  "conversationId": "my-chat-001"
}
```

| 字段 | 必填 | 规则 |
|------|------|------|
| model | ✅ | 最多 100 字符。支持复合格式 `providerId/modelId`（如 `zhipu/glm-5.1`）或简单格式（如 `deepseek-chat`，默认路由） |
| message | ✅ | 最多 10000 字符 |
| conversationId | | 字母/数字/下划线/连字符，默认 `"default"` |

**Response：**

```json
{
  "model": "deepseek-v4-flash",
  "content": "你好！有什么可以帮你的？",
  "conversationId": "my-chat-001"
}
```

> **模型路由规则：** `model` 字段支持两种格式：
> - **复合格式** `{providerId}/{modelId}`：精确路由，如 `zhipu/glm-5.1`、`minimax/MiniMax-M2.1`
> - **简单格式** `{modelId}`：路由到默认 Provider（`deepseek`），如 `deepseek-v4-flash`
>
> 推荐使用复合格式，避免同名模型冲突。

---

### GET /api/chat/stream

SSE 流式聊天（query params）。

**权限：** `chat:stream`

**Params：**

| 参数 | 必填 | 说明 |
|------|------|------|
| model | ✅ | 模型 ID（同 POST /api/chat 的路由规则） |
| message | ✅ | 消息内容 |
| conversationId | | 对话 ID |

**Response：** `text/event-stream`

```
data: {"content":"你","model":"deepseek-v4-flash","conversationId":"default"}
data: {"content":"好","model":"deepseek-v4-flash","conversationId":"default"}
data: [DONE]
```

---

### POST /api/chat/stream

SSE 流式聊天（JSON body）。

**权限：** `chat:stream`

**Request：** 同 POST /api/chat

**Response：** 同 GET /api/chat/stream

---

### POST /api/models/refresh

手动刷新模型列表（从所有 Provider API 重新拉取）。

**权限：** `model:config`

**Response（成功）：**

```json
{ "message": "Models refreshed successfully" }
```

**Response（部分失败）：**

```json
{ "message": "Failed to refresh models, existing models remain available" }
```

> 单个 Provider 拉取失败不影响其他 Provider，已缓存的模型仍然可用。

---

## 对话管理

> 所有对话接口自动绑定当前登录用户，用户只能查看和管理自己的对话。

### GET /api/conversations

对话列表（分页）。

**权限：** `conversation:manage`

**Params：**

| 参数 | 必填 | 说明 |
|------|------|------|
| page | | 页码，默认 1 |
| size | | 每页条数，默认 50 |

**Response：**

```json
[
  {
    "conversationId": "my-chat-001",
    "messageCount": 5,
    "firstMessageAt": "2026-05-08T10:00:00",
    "lastMessageAt": "2026-05-08T10:05:00"
  }
]
```

---

### GET /api/conversations/{conversationId}

对话消息明细。

**权限：** `conversation:manage`

**Response：**

```json
[
  { "role": "user", "content": "你好", "createdAt": "2026-05-08T10:00:00" },
  { "role": "assistant", "content": "你好！有什么可以帮你的？", "createdAt": "2026-05-08T10:00:01" }
]
```

---

### DELETE /api/conversations/{conversationId}

清空指定对话的所有消息。

**权限：** `conversation:manage`

**Response：**

```json
{
  "conversationId": "my-chat-001",
  "message": "对话已清空"
}
```

---

### GET /api/conversations/{conversationId}/export

导出对话记录。

**权限：** `conversation:manage`

**Response：**

```json
{
  "conversationId": "my-chat-001",
  "messageCount": 5,
  "messages": [
    { "role": "user", "content": "你好", "createdAt": "2026-05-08T10:00:00" },
    { "role": "assistant", "content": "你好！", "createdAt": "2026-05-08T10:00:01" }
  ]
}
```

---

## 系统提示词

### GET /api/prompts

获取所有模型的 System Prompt。

**权限：** `prompt:manage`

---

### GET /api/prompts/{modelId}

获取指定模型的 System Prompt。

**权限：** `prompt:manage`

---

### PUT /api/prompts/{modelId}

设置指定模型的 System Prompt。

**权限：** `prompt:manage`

**Request：**

```json
{ "promptText": "你是一个有用的助手。" }
```

---

### DELETE /api/prompts/{modelId}

删除指定模型的 System Prompt。

**权限：** `prompt:manage`

**Response（成功）：**

```json
{ "modelId": "deepseek-chat", "message": "已删除" }
```

---

## 模型参数

### GET /api/models/params

获取所有模型的参数配置。

**权限：** `model:config`

---

### GET /api/models/{modelId}/params

获取指定模型的参数。

**权限：** `model:config`

**Response（有配置时）：**

```json
{
  "modelId": "deepseek-v4-flash",
  "temperature": 0.7,
  "maxTokens": 4096,
  "topP": 0.9,
  "frequencyPenalty": 0.0,
  "presencePenalty": 0.0
}
```

**Response（无配置）：** 204 No Content

---

### PUT /api/models/{modelId}/params

创建或更新模型参数。只更新非 null 字段，null 字段保持原值。

**权限：** `model:config`

**Request：**

```json
{
  "temperature": 0.7,
  "maxTokens": 4096,
  "topP": 0.9,
  "frequencyPenalty": 0.0,
  "presencePenalty": 0.0
}
```

---

### DELETE /api/models/{modelId}/params

删除模型参数（恢复默认）。

**权限：** `model:config`

**Response（成功）：**

```json
{ "modelId": "deepseek-v4-flash", "message": "已删除，恢复默认参数" }
```

**Response（无配置）：** 404 Not Found

---

## 用量统计

> 所有用量查询自动绑定当前登录用户，用户只能查看自己的用量数据。

### GET /api/usage/records

Token 用量明细（必须指定 `model` 或 `conversation` 参数）。

**权限：** `usage:view`

**Params：**

| 参数 | 必填 | 说明 |
|------|------|------|
| model | 二选一 | 按模型筛选 |
| conversation | 二选一 | 按对话 ID 筛选 |

**Response：**

```json
[
  {
    "conversationId": "u_1_my-chat-001",
    "modelId": "deepseek-v4-flash",
    "promptTokens": 120,
    "completionTokens": 80,
    "totalTokens": 200,
    "durationMs": 1500,
    "createdAt": "2026-05-08T10:00:01"
  }
]
```

---

### GET /api/usage/stats/model

按模型聚合统计。

**权限：** `usage:view`

**Params：**

| 参数 | 必填 | 说明 |
|------|------|------|
| model | | 按模型筛选 |
| startTime | | ISO 时间，如 `2026-05-01T00:00:00` |
| endTime | | ISO 时间 |

**Response：**

```json
[
  {
    "groupKey": "deepseek-v4-flash",
    "requestCount": 150,
    "totalPromptTokens": 30000,
    "totalCompletionTokens": 15000,
    "totalTokens": 45000,
    "avgDurationMs": 1200.5
  }
]
```

---

### GET /api/usage/stats/conversation

按对话聚合统计。

**权限：** `usage:view`

**Params：**

| 参数 | 必填 | 说明 |
|------|------|------|
| conversation | | 按对话 ID 筛选 |
| startTime | | ISO 时间 |
| endTime | | ISO 时间 |

**Response：** 同 `stats/model`，`groupKey` 为对话 ID

---

## 用户管理

> 以下接口均需要 `user:manage` 权限（管理员）。

### GET /api/users

用户列表（分页）。

**Params：**

| 参数 | 必填 | 说明 |
|------|------|------|
| page | | 页码，默认 1 |
| size | | 每页条数，默认 20 |
| keyword | | 搜索关键词（模糊匹配用户名/昵称/邮箱） |

---

### GET /api/users/{id}

用户详情。

---

### PATCH /api/users/{id}

修改用户信息。

**Request：**

```json
{
  "nickname": "新昵称",
  "email": "new@example.com",
  "phone": "13800138000",
  "avatar": "https://..."
}
```

---

### PATCH /api/users/{id}/status

启用/禁用用户。

**Params：**

| 参数 | 必填 | 说明 |
|------|------|------|
| status | ✅ | `0` 禁用，`1` 启用 |

---

### PATCH /api/users/{id}/roles

分配角色。

**Request：**

```json
{ "roleIds": [1, 2] }
```

---

### DELETE /api/users/{id}

逻辑删除用户。

---

## 角色权限

> 以下接口均需要 `role:manage` 权限（管理员）。

### GET /api/roles

角色列表。

---

### GET /api/roles/{id}

角色详情（含权限列表）。

---

### POST /api/roles

创建角色。

**Request：**

```json
{
  "roleName": "operator",
  "roleDesc": "运营人员"
}
```

---

### PUT /api/roles/{id}

更新角色。

**Request：**

```json
{ "roleDesc": "新描述" }
```

---

### DELETE /api/roles/{id}

逻辑删除角色。

**Response：**

```json
{ "roleId": "3", "message": "角色已删除" }
```

---

### GET /api/roles/{id}/permissions

获取角色的权限列表。

---

### PATCH /api/roles/{id}/permissions

分配权限给角色。

**Request：**

```json
{ "permissionIds": [1, 2, 3] }
```

**Response：**

```json
{
  "roleId": 1,
  "permissionIds": [1, 2, 3],
  "message": "权限已更新"
}
```

---

### GET /api/roles/permissions

获取所有权限列表。

---

## 通用错误

所有错误返回统一格式：

```json
{
  "error": "error_type",
  "message": "友好描述",
  "status": 400
}
```

| HTTP 状态码 | error | 说明 |
|------------|-------|------|
| 400 | `business_error` | 业务逻辑错误 |
| 400 | `validation_error` | 参数校验失败（含字段级详情） |
| 400 | `content_filtered` | 内容包含敏感词 |
| 401 | `UNAUTHORIZED` | 未认证 / Token 失效 |
| 403 | `FORBIDDEN` | 权限不足 |
| 404 | `model_not_found` | 模型不存在 |
| 429 | `rate_limit_exceeded` | 请求过于频繁 |
| 500 | `internal_error` | 服务内部错误 |

> `validation_error` 的 `message` 字段包含具体校验失败的字段名和原因，例如 `"model: 模型不能为空; message: 消息不能为空"`。
