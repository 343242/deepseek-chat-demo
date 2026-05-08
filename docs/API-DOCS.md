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

**Response：** 用户信息（Token 已写入 Cookie）

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

**Response：** 更新后的用户信息

---

## 聊天

### GET /api/models

获取可用模型列表。

**权限：** `chat:send`

**Response：**

```json
[
  { "id": "deepseek-chat", "object": "model", "created": 1700000000, "owned_by": "deepseek" },
  { "id": "deepseek-reasoner", "object": "model", "created": 1700000000, "owned_by": "deepseek" }
]
```

---

### POST /api/chat

阻塞式聊天。

**权限：** `chat:send`

**Request：**

```json
{
  "model": "deepseek-chat",
  "message": "你好",
  "conversationId": "my-chat-001"
}
```

| 字段 | 必填 | 规则 |
|------|------|------|
| model | ✅ | 最多 100 字符 |
| message | ✅ | 最多 10000 字符 |
| conversationId | | 字母/数字/下划线/连字符，默认 "default" |

**Response：**

```json
{
  "model": "deepseek-chat",
  "content": "你好！有什么可以帮你的？",
  "conversationId": "my-chat-001"
}
```

---

### GET /api/chat/stream

SSE 流式聊天（query params）。

**权限：** `chat:stream`

**Params：**

| 参数 | 必填 | 说明 |
|------|------|------|
| model | ✅ | 模型 ID |
| message | ✅ | 消息内容 |
| conversationId | | 对话 ID |

**Response：** `text/event-stream`

```
data: {"content":"你","model":"deepseek-chat","conversationId":"default"}
data: {"content":"好","model":"deepseek-chat","conversationId":"default"}
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

手动刷新模型列表（从 DeepSeek API 拉取）。

**权限：** `model:config`

**Response：** 更新后的模型列表

---

## 对话管理

### GET /api/conversations

对话列表（分页）。

**权限：** `conversation:manage`

**Params：**

| 参数 | 必填 | 说明 |
|------|------|------|
| page | | 页码，默认 1 |
| size | | 每页条数，默认 20 |

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

**Response：** 204 No Content

---

### GET /api/conversations/{conversationId}/export

导出对话为 JSON 文件。

**权限：** `conversation:manage`

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

---

## 模型参数

### GET /api/models/params

获取所有模型的参数配置。

**权限：** `model:config`

---

### GET /api/models/{modelId}/params

获取指定模型的参数。

**权限：** `model:config`

**Response：**

```json
{
  "modelId": "deepseek-chat",
  "temperature": 0.7,
  "maxTokens": 4096,
  "topP": 0.9,
  "frequencyPenalty": 0.0,
  "presencePenalty": 0.0
}
```

---

### PUT /api/models/{modelId}/params

设置模型参数。

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

---

## 用量统计

### GET /api/usage/records

Token 用量记录（分页）。

**权限：** `usage:view`

**Params：** `page`, `size`

---

### GET /api/usage/stats/model

按模型聚合统计。

**权限：** `usage:view`

**Response：**

```json
[
  {
    "groupKey": "deepseek-chat",
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

---

## 用户管理

> 以下接口均需要 `user:manage` 权限（管理员）。

### GET /api/users

用户列表（分页）。

**Params：** `page`, `size`

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

**Request：**

```json
{ "status": 0 }
```

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

角色详情。

---

### POST /api/roles

创建角色。

---

### PUT /api/roles/{id}

更新角色。

---

### DELETE /api/roles/{id}

逻辑删除角色。

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
| 400 | `validation_error` | 参数校验失败 |
| 400 | `content_filtered` | 内容包含敏感词 |
| 401 | `unauthorized` | 未认证 / Token 失效 |
| 403 | `access_denied` | 权限不足 |
| 404 | `not_found` | 资源不存在 / 模型不存在 |
| 429 | `rate_limit_exceeded` | 请求过于频繁 |
| 500 | `internal_error` | 服务内部错误 |
