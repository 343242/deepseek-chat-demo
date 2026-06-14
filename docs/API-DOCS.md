# API 接口文档

> Base URL: `http://localhost:8080`
>
> 认证方式：Token 写入 HttpOnly Cookie (`access_token`)，也可通过 `Authorization: Bearer <token>` 传递

## 模型 ID 约定（全局）

**所有 API 中出现的 `model` / `modelId` 字段必须使用 registry 候选 ID 格式**（如 `deepseek-v4-flash`、`qwen-plus`、`qwen3-max`），**不接受 `provider/modelId` 复合格式**（如 `deepseek/deepseek-v4-flash`）。

- 检测点：`ChatServiceImpl.resolveCandidateId` 在请求入口对 `model` 字段做格式校验，检测到 `/` 立即 fail-fast 抛 `IllegalArgumentException`，被映射为业务码 **100001（BAD_REQUEST）**
- 响应字段 `compositeId` / `modelId` 的**字段名是历史遗留**，**值就是单段 registry 候选 ID**（不再是"复合"语义）
- 候选与厂商映射在 `application.yml` 的 `app.llm.capabilities.{chat,embedding,reranking}.candidates[]` 中声明，启动期绑定至 `LlmClientRegistry`
- 完整契约见 [`.trellis/spec/backend/llm-spi.md`](../.trellis/spec/backend/llm-spi.md)

## 统一响应格式

所有非流式接口统一返回 `GlobalResponse` 格式：

**成功响应：**
```json
{"code": 0, "message": "ok", "data": {...}}
```

**失败响应：**
```json
{"code": 40005, "message": "用户名已存在", "data": null}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | int | 状态码，0=成功，非0=错误码 |
| message | string | 友好提示 |
| data | any | 业务数据（成功时有值，失败时为 null） |

> SSE 流式接口（`/api/chat/stream`）保持 `text/event-stream` 格式不变，不走 GlobalResponse 包装。
>
> 以下文档中 Response 示例仅展示 `data` 字段内容（外层 `code`/`message` 省略）。

### 错误码分段

| 范围 | 模块 |
|------|------|
| 0 | 成功 |
| 40000-40099 | 通用（参数/校验/权限） |
| 40100 | 未认证 |
| 40300 | 权限不足 |
| 40400 | 资源不存在 |
| 42900 | 限流 |
| 50000 | 内部错误 |
| 10xxx | 认证（登录/注册/验证码/Token） |
| 20xxx | 用户管理（用户/角色/权限） |
| 30xxx | 会话管理 |
| 40xxx | 聊天（模型/内容过滤） |
| 50xxx | RAG（文档/ETL） |
| 60xxx | 团队（创建/成员/审批） |

---

## 目录

- [认证](#认证)
- [聊天](#聊天)
- [对话管理](#对话管理)
- [系统提示词](#系统提示词)
- [模型参数](#模型参数)
- [RAG 文档管理](#rag-文档管理)
  - [分片上传](#分片上传)
- [用量统计](#用量统计)
- [用户管理](#用户管理)
- [角色权限](#角色权限)
- [团队](#团队)
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
    "compositeId": "deepseek-v4-flash",
    "ownedBy": "deepseek",
    "created": 1773799200
  },
  {
    "id": "glm-5.1",
    "providerId": "zhipu",
    "providerName": "智谱 AI",
    "compositeId": "glm-5.1",
    "ownedBy": "zhipuai",
    "created": 0
  },
  {
    "id": "MiniMax-M2.1",
    "providerId": "minimax",
    "providerName": "MiniMax",
    "compositeId": "MiniMax-M2.1",
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
| compositeId | 候选 ID（registry candidate ID），与 LlmClientRegistry 注册一致，用于 API 请求时精确路由 |

---

### POST /api/chat

阻塞式聊天。

**权限：** `chat:send`

**Request：**

```json
{
  "model": "deepseek-v4-flash",
  "message": "你好",
  "conversationId": "my-chat-001"
}
```

| 字段 | 必填 | 规则 |
|------|------|------|
| model | ✅ | 最多 100 字符。**registry 候选 ID**（如 `deepseek-v4-flash`），与 `LlmClientRegistry` 注册一致；不接受 `providerId/modelId` 复合格式（fail-fast 返回 400） |
| message | ✅ | 最多 10000 字符 |
| conversationId | | 字母/数字/下划线/连字符，默认 `"default"` |
| ragEnabled | | Boolean，默认 false。启用后通过 RAG 检索增强回答 |
| mode | | `SIMPLE`（单轮）或 `MULTI_TURN`（多轮，自动维护会话记忆），默认 SIMPLE |
| enableThinking | | Boolean，默认 false。仅 MULTI_TURN 模式生效，启用思考过程输出 |

**Response：**

```json
{
  "model": "deepseek-v4-flash",
  "content": "你好！有什么可以帮你的？",
  "conversationId": "my-chat-001"
}
```

> **模型路由规则：** `model` 字段必须为 **registry 候选 ID**（candidate ID，如 `deepseek-v4-flash`），
> 即 `LlmClientRegistry` 中 `app.llm.providers.{provider}.chat.candidates[].id` 注册的值。
> 不接受 `providerId/modelId` 复合格式——若传入带 `/` 的值，服务端 fail-fast 返回 400 + 明确错误信息。

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
| ragEnabled | | Boolean，默认 false。启用后通过 RAG 检索增强回答 |
| mode | | `SIMPLE`（单轮）或 `MULTI_TURN`（多轮），默认 SIMPLE |

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

## 会话管理

> 所有会话接口自动绑定当前登录用户，用户只能查看和管理自己的会话。
>
> **conversationId 格式：** 前端传入原始 ID（如 `01913a5c8b3a4f2ea1b0c3d4e5f60789`），后端自动拼接用户隔离前缀 `u_{userId}_{rawId}`。
>
> 新创建的会话使用 **UUIDv7**（RFC 9562）作为原始 ID——基于 Unix 毫秒时间戳，天然有序，全局唯一。

### POST /api/conversations

创建新会话。

**权限：** `conversation:manage`

**Request：**

```json
{
  "title": "我的新会话",
  "modelId": "deepseek-v4-flash"
}
```

| 字段 | 必填 | 规则 |
|------|------|------|
| title | | 最多 200 字符。不传则系统从首条消息自动截取前 20 字 |
| modelId | | 最多 100 字符。**registry 候选 ID**（如 `deepseek-v4-flash`），不接受 `provider/modelId` 复合格式 |

**Response：**

```json
{
  "id": 1,
  "conversationId": "01913a5c8b3a4f2ea1b0c3d4e5f60789",
  "title": "我的新会话",
  "titleSource": "USER",
  "modelId": "deepseek-v4-flash",
  "pinned": false,
  "status": "ACTIVE",
  "messageCount": 0,
  "lastMessageAt": null,
  "createdAt": "2026-05-12T15:30:00"
}
```

> `titleSource` 取值：`SYSTEM`（系统自动生成）| `USER`（用户手动设置）

---

### GET /api/conversations

会话列表（分页，置顶优先，按最后消息时间降序）。

**权限：** `conversation:manage`

**Params：**

| 参数 | 必填 | 说明 |
|------|------|------|
| page | | 页码，默认 1，最小 1 |
| size | | 每页条数，默认 50，最大 500 |
| status | | 状态过滤：`ACTIVE` / `ARCHIVED`。不传则返回所有非删除会话 |

**Response：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "content": [
      {
        "id": 1,
        "conversationId": "01913a5c8b3a4f2ea1b0c3d4e5f60789",
        "title": "你好…",
        "titleSource": "SYSTEM",
        "modelId": "deepseek-v4-flash",
        "pinned": true,
        "status": "ACTIVE",
        "messageCount": 12,
        "lastMessageAt": "2026-05-12T16:00:00",
        "createdAt": "2026-05-12T15:30:00"
      }
    ],
    "page": 1,
    "size": 50,
    "total": 1,
    "totalPages": 1
  }
}
```

---

### GET /api/conversations/{conversationId}

获取会话详情（含消息树）。

**权限：** `conversation:manage`

**Response：**

```json
{
  "id": 1,
  "conversationId": "01913a5c8b3a4f2ea1b0c3d4e5f60789",
  "title": "你好…",
  "titleSource": "SYSTEM",
  "modelId": "deepseek-v4-flash",
  "pinned": false,
  "status": "ACTIVE",
  "messageCount": 4,
  "lastMessageAt": "2026-05-12T16:00:00",
  "createdAt": "2026-05-12T15:30:00",
  "messages": [
    {
      "id": 1,
      "parentId": null,
      "role": "USER",
      "content": "你好",
      "status": "FINISHED",
      "createdAt": "2026-05-12T15:30:00",
      "children": []
    },
    {
      "id": 2,
      "parentId": 1,
      "role": "ASSISTANT",
      "content": "你好！有什么可以帮你的？",
      "status": "FINISHED",
      "modelId": "deepseek-v4-flash",
      "tokenUsage": 200,
      "durationMs": 1500,
      "createdAt": "2026-05-12T15:30:01",
      "children": []
    }
  ]
}
```

> 消息通过 `parentId` 构成树形结构，支持分支对话和重新生成。当前仅加载一层子节点（直接回复）。

---

### GET /api/conversations/{conversationId}/messages

获取会话的消息列表（树形结构）。

**权限：** `conversation:manage`

**Response：** 同 `GET /api/conversations/{id}` 的 `messages` 字段，返回消息树数组。

**Response（会话无消息）：** 404 Not Found

---

### PUT /api/conversations/{conversationId}

更新会话（标题/置顶/归档）。

**权限：** `conversation:manage`

**Request：**

```json
{
  "title": "新标题",
  "pinned": true,
  "status": "ARCHIVED"
}
```

| 字段 | 必填 | 规则 |
|------|------|------|
| title | | 最多 200 字符。设置后 `titleSource` 自动变为 `USER` |
| pinned | | `true` 置顶，`false` 取消置顶 |
| status | | 仅允许 `ACTIVE` 或 `ARCHIVED` |

> 所有字段均可选，不传则不更新。

**Response：**

```json
{
  "conversationId": "01913a5c8b3a4f2ea1b0c3d4e5f60789",
  "message": "会话已更新"
}
```

---

### DELETE /api/conversations/{conversationId}

删除会话（软删除 + 清空消息）。

**权限：** `conversation:manage`

> 删除操作在事务中执行：会话标记为 `DELETED` + message 表记录物理删除。事务外清空 Spring AI chat memory。

**Response：**

```json
{
  "conversationId": "01913a5c8b3a4f2ea1b0c3d4e5f60789",
  "message": "会话已删除"
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
{ "modelId": "deepseek-v4-flash", "message": "已删除" }
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

## RAG 文档管理

> 所有文档接口需要登录（`@PreAuthorize("isAuthenticated()")`），且自动绑定当前用户，只能查看和管理自己的文档。

### POST /api/documents/upload

上传单个文档并触发 ETL 处理。

**权限：** 需要登录

**Request：** `multipart/form-data`

| 参数 | 必填 | 说明 |
|------|------|------|
| file | ✅ | 上传的文档文件 |

**Response：**

```json
{
  "id": 1,
  "fileName": "report.pdf",
  "status": "UPLOADED"
}
```

> 文档上传后自动进入 ETL 流水线：UPLOADED → PARSING → CHUNKING → VECTORIZING → COMPLETED / FAILED。

---

### POST /api/documents/upload/batch

批量上传文档。根据文档数量和总大小自动路由处理策略：
- 小批量（≤10 个且 ≤5MB）→ 快速通道（BM25 先行 + 异步向量化）
- 其他 → 标准并发 ETL

**权限：** 需要登录

**Request：** `multipart/form-data`

| 参数 | 必填 | 说明 |
|------|------|------|
| files | ✅ | 上传的文档文件数组 |

**Response：**

```json
[
  { "id": 1, "fileName": "doc1.pdf", "status": "UPLOADED" },
  { "id": 2, "fileName": "doc2.pdf", "status": "UPLOADED" }
]
```

---

### GET /api/documents

获取文档列表（仅当前用户的文档，按创建时间倒序）。

**权限：** 需要登录

**Response：**

```json
[
  {
    "id": 1,
    "fileName": "report.pdf",
    "fileSize": 1048576,
    "mimeType": "application/pdf",
    "chunkCount": 42,
    "status": "COMPLETED",
    "errorMessage": null,
    "userId": 1,
    "createTime": "2026-05-10T14:30:00"
  }
]
```

**DocumentDTO 字段说明：**

| 字段 | 说明 |
|------|------|
| id | 文档 ID |
| fileName | 文件名 |
| fileSize | 文件大小（字节） |
| mimeType | MIME 类型 |
| chunkCount | 分块数量 |
| status | 处理状态：UPLOADED / PARSING / CHUNKING / VECTORIZING / COMPLETED / FAILED |
| errorMessage | 失败时的错误信息 |
| userId | 所属用户 ID |
| createTime | 创建时间 |

---

### GET /api/documents/{id}

获取文档详情。

**权限：** 需要登录

**Response：** 单个 DocumentDTO（同上）

**Response（未找到）：** 404 Not Found

---

### DELETE /api/documents/{id}

删除文档（含存储 + 向量 + 元数据清理）。仅文档所有者可操作。

**权限：** 需要登录

**Response（成功）：** 204 No Content

**Response（未找到）：** 404 Not Found

---

### GET /api/documents/{id}/status

查询文档处理状态。

**权限：** 需要登录

**Response：** 单个 DocumentDTO（同上）

**Response（未找到）：** 404 Not Found

---

**文档状态流转：**

```
UPLOADED → PARSING → CHUNKING → VECTORIZING → COMPLETED
                                                ↘ FAILED
```

| 状态 | 说明 |
|------|------|
| UPLOADED | 已上传，等待解析 |
| PARSING | 正在解析文档内容 |
| CHUNKING | 正在分块 |
| VECTORIZING | 正在生成向量嵌入 |
| COMPLETED | 处理完成，可用于 RAG 检索 |
| FAILED | 处理失败，查看 errorMessage 了解原因 |

---

### 分片上传

> 支持大文件（≤50MB）分片上传，提供秒传、断点续传、异步合并。详细设计见 [分片上传设计文档](design/chunk-upload.md)。

#### POST /api/documents/multipart

创建分片上传会话。根据 `fileMd5` 自动判断秒传、新建或续传。

**权限：** 需要登录

**Request：**

```json
{
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e",
  "fileName": "report.pdf",
  "fileSize": 52428800,
  "mimeType": "application/pdf",
  "totalChunks": 10
}
```

**Response（秒传 — 200）：**

```json
{
  "uploaded": true,
  "documentId": 42
}
```

**Response（新建 — 201）：**

```json
{
  "uploaded": false,
  "uploadId": "550e8400-e29b-41d4-a716-446655440000",
  "chunkSize": 5242880
}
```

**Response（续传 — 201）：**

```json
{
  "uploaded": false,
  "uploadId": "550e8400-e29b-41d4-a716-446655440000",
  "chunkSize": 5242880,
  "uploadedChunks": [0, 1, 2, 5]
}
```

---

#### PUT /api/documents/multipart/{uploadId}/chunks/{chunkIndex}

上传单个分片。最后一个分片上传完成后自动触发合并。

**权限：** 需要登录

**Headers：**

| Header | 必填 | 说明 |
|--------|------|------|
| `X-Chunk-MD5` | ✅ | 分片 MD5（32 位 hex） |

**Request Body：** `application/octet-stream`（分片二进制数据）

**Response（200）：**

```json
{
  "uploaded": true,
  "chunkIndex": 3,
  "autoMerged": false
}
```

> `autoMerged=true` 表示最后一个分片已自动触发合并，客户端无需再调用 complete。

---

#### GET /api/documents/multipart/{uploadId}

查询上传状态。用于断点续传时获取已上传分片列表。

**权限：** 需要登录

**Response（200）：**

```json
{
  "uploadId": "550e8400-e29b-41d4-a716-446655440000",
  "fileName": "report.pdf",
  "totalChunks": 10,
  "uploadedChunks": [0, 1, 2, 5, 6, 7],
  "chunkSize": 5242880
}
```

---

#### POST /api/documents/multipart/{uploadId}/complete

显式触发合并。通常在 auto-merge 失败后调用重试。

**权限：** 需要登录

**Request（可选）：**

```json
{
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e"
}
```

**Response（202 Accepted）：**

```json
{
  "documentId": 42,
  "status": "MERGING"
}
```

---

#### DELETE /api/documents/multipart/{uploadId}

取消上传，清理 Redis session 和 MinIO 临时分片。

**权限：** 需要登录

**Response：** 204 No Content

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

**Response：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "content": [
      {"id": 1, "username": "admin", "nickname": "管理员", "status": 1}
    ],
    "page": 1,
    "size": 20,
    "total": 1,
    "totalPages": 1
  }
}
```

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

**Response：**

```json
{
  "userId": 1,
  "status": 0,
  "message": "已禁用"
}
```

---

### PATCH /api/users/{id}/roles

分配角色。

**Request：**

```json
{ "roleIds": [1, 2] }
```

**Response：**

```json
{
  "userId": 1,
  "roleIds": [1, 2],
  "message": "角色已更新"
}
```

---

### DELETE /api/users/{id}

逻辑删除用户。

**Response：**

```json
{
  "userId": 1,
  "message": "用户已删除"
}
```

---

## 角色权限

> 以下接口均需要 `role:manage` 权限（管理员）。

### GET /api/roles

角色列表。

---

### GET /api/roles/{id}

角色详情（含权限列表）。

**Response：**

```json
{
  "role": {
    "id": 1,
    "roleName": "ADMIN",
    "roleDesc": "管理员",
    "deleted": 0,
    "createdAt": "2026-05-01T00:00:00+08:00",
    "updatedAt": "2026-05-01T00:00:00+08:00"
  },
  "permissions": [
    {"id": 1, "permissionName": "chat:send", "resourceKey": "chat", "action": "send"}
  ]
}
```

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

所有错误返回 GlobalResponse 格式（与成功响应结构一致）：

```json
{
  "code": 40001,
  "message": "参数校验失败",
  "data": null
}
```

| HTTP 状态码 | code 范围 | 说明 |
|------------|----------|------|
| 400 | 10xxx~50xxx | 业务逻辑错误 / 参数校验失败 / 敏感词过滤 |
| 401 | 40100 | 未认证 / Token 失效 |
| 403 | 40300 | 权限不足 |
| 404 | 40400 | 资源不存在（模型/文档/用户等） |
| 429 | 42900 | 请求过于频繁 |
| 500 | 50000 | 服务内部错误 |

> `40001`（参数校验失败）的 `message` 字段包含具体校验失败的字段名和原因，例如 `"model: 模型不能为空; message: 消息不能为空"`。
>
> 完整错误码列表见上方「错误码分段」表格。

---

## 团队

> 所有团队接口需要认证（Cookie `access_token`）。
>
> 权限要求：团队操作需是团队成员，管理操作需 `ADMIN` 或 `CREATOR` 角色。

### POST /api/teams

创建团队。需要登录。

**Request：**

```json
{
  "teamName": "我的团队",
  "teamDesc": "这是一个协作团队"
}
```

| 字段 | 必填 | 规则 |
|------|------|------|
| teamName | ✅ | 团队名称，非空，≤128 字符 |
| teamDesc | | 团队描述，≤512 字符 |

**Response（TeamVO）：**

```json
{
  "id": 1,
  "teamName": "我的团队",
  "teamDesc": "这是一个协作团队",
  "creatorId": 1,
  "memberCount": 1,
  "myRole": "CREATOR",
  "createdAt": "2026-05-14T10:00:00+08:00"
}
```

> 创建者自动获得 `CREATOR` 角色，无需额外加入操作。

---

### GET /api/teams

查看我的团队列表。需要登录。

**Response（List<TeamSearchResultVO>）：**

```json
[
  {
    "id": 1,
    "teamName": "我的团队",
    "teamDesc": "这是一个协作团队",
    "memberCount": 5,
    "creatorName": "admin"
  }
]
```

| 字段 | 说明 |
|------|------|
| memberCount | 团队成员数量 |
| creatorName | 创建者昵称 |

---

### GET /api/teams/{teamId}

查看团队详情。需是团队成员。

**Response（TeamDetailVO）：**

```json
{
  "id": 1,
  "teamName": "我的团队",
  "teamDesc": "这是一个协作团队",
  "creatorId": 1,
  "creatorName": "admin",
  "memberCount": 5,
  "documentCount": 12,
  "defaultUploadLimitMb": 50,
  "creatorUploadLimitMb": 200,
  "myRole": "CREATOR",
  "createdAt": "2026-05-14T10:00:00+08:00"
}
```
```

---

### PUT /api/teams/{teamId}

更新团队信息。需是团队成员（`ADMIN` 或 `CREATOR` 角色）。

**Request（TeamUpdateRequest）：**

```json
{
  "teamName": "新团队名称",
  "teamDesc": "更新后的描述"
}
```

| 字段 | 必填 | 规则 |
|------|------|------|
| teamName | | 新的团队名称 |
| teamDesc | | 新的团队描述 |

> 所有字段均可选，不传则不更新。

**Response：** 返回更新后的 TeamDetailVO（同上）。

---

### DELETE /api/teams/{teamId}

解散团队。仅创建者（`CREATOR`）可操作。

**Response：** `200 OK`（无返回体）

> 解散团队将移除所有成员及团队相关数据。

---

### PUT /api/teams/{teamId}/creator-quota

设置创建者存储额度。仅创建者（`CREATOR`）可操作。

**Request（CreatorQuotaRequest）：**

```json
{
  "maxUploadMb": 500
}
```

| 字段 | 必填 | 规则 |
|------|------|------|
| maxUploadMb | ✅ | 最大上传额度（MB），最小 1 |

**Response：** `200 OK`（无返回体）

---

## 团队成员

> 以下接口前缀均为 `/api/teams/{teamId}/members`，所有接口需要登录且是团队成员。

### POST /api/teams/{teamId}/members/{userId}

邀请成员加入团队。需是创建者或管理员（`CREATOR` / `ADMIN`）。

**Path 参数：**

| 参数 | 说明 |
|------|------|
| teamId | 团队 ID |
| userId | 被邀请的用户 ID |

**Response（TeamMemberVO）：**

```json
{
  "userId": 2,
  "username": "alice",
  "nickname": "Alice",
  "role": "MEMBER",
  "uploadLimitMb": 50,
  "joinedAt": "2026-05-14T10:30:00+08:00"
}
```

> 新成员默认角色为 `MEMBER`，额度继承团队默认值 50MB。

---

### DELETE /api/teams/{teamId}/members/{userId}

移除成员。需是创建者或管理员（`CREATOR` / `ADMIN`）。不能移除自己。

**Response：** `200 OK`（无返回体）

---

### POST /api/teams/{teamId}/members/leave

退出团队。任何成员均可操作。创建者不能退出（需先转让或解散）。

**Response：** `200 OK`（无返回体）

---

### PUT /api/teams/{teamId}/members/{userId}/role

修改成员角色。仅创建者（`CREATOR`）可操作。

**Request（MemberRoleUpdateRequest）：**

```json
{
  "targetRole": "ADMIN"
}
```

| 字段 | 必填 | 规则 |
|------|------|------|
| targetRole | ✅ | 目标角色：`ADMIN` / `MEMBER` |

> `CREATOR` 角色不可转让，不可将自己降级。

**Response：** `200 OK`（无返回体）

---

### PUT /api/teams/{teamId}/members/{userId}/upload-limit

设置成员上传额度。需是创建者或管理员（`CREATOR` / `ADMIN`）。

**Request（MemberUploadLimitRequest）：**

```json
{
  "uploadLimitMb": 200
}
```

| 字段 | 必填 | 规则 |
|------|------|------|
| uploadLimitMb | ✅ | 上传额度（MB），1~10240 |

**Response：** `200 OK`（无返回体）

---

### GET /api/teams/{teamId}/members

成员列表（分页）。需是团队成员。

**Params：**

| 参数 | 必填 | 说明 |
|------|------|------|
| page | | 页码，默认 1 |
| size | | 每页条数，默认 20 |

**Response（PagedResult\<TeamMemberVO\>）：**

```json
{
  "content": [
    {
      "userId": 1,
      "username": "admin",
      "nickname": "管理员",
      "role": "CREATOR",
      "uploadLimitMb": 200,
      "joinedAt": "2026-05-14T10:00:00+08:00"
    },
    {
      "userId": 2,
      "username": "alice",
      "nickname": "Alice",
      "role": "MEMBER",
      "uploadLimitMb": 50,
      "joinedAt": "2026-05-14T10:30:00+08:00"
    }
  ],
  "page": 1,
  "size": 20,
  "total": 2,
  "totalPages": 1
}
```

---

## 团队审批

> 以下接口前缀均为 `/api/teams/{teamId}/approvals`，所有接口需要登录且是团队成员（管理接口需 `ADMIN` / `CREATOR` 角色）。

### GET /api/teams/{teamId}/approvals/pending

待审批文档列表（分页）。需是管理员或创建者（`ADMIN` / `CREATOR`）。

**Params：**

| 参数 | 必填 | 说明 |
|------|------|------|
| page | | 页码，默认 1 |
| size | | 每页条数，默认 20 |

**Response（PagedResult\<ApprovalVO\>）：**

```json
{
  "content": [
    {
      "id": 1,
      "documentId": 42,
      "fileName": "report.pdf",
      "fileSize": 1048576,
      "uploaderId": 3,
      "uploaderName": "bob",
      "status": "PENDING",
      "reviewerId": null,
      "reviewComment": null,
      "createdAt": "2026-05-14T11:00:00+08:00",
      "reviewedAt": null
    }
  ],
  "page": 1,
  "size": 20,
  "total": 1,
  "totalPages": 1
}
```

| 字段 | 说明 |
|------|------|
| documentId | 上传的文档 ID |
| fileName | 文档文件名 |
| uploaderId / uploaderName | 上传者信息 |
| status | `PENDING` / `APPROVED` / `REJECTED` |

---

### POST /api/teams/{teamId}/approvals/{approvalId}/review

审批操作（通过/拒绝）。需是管理员或创建者（`ADMIN` / `CREATOR`）。

**Request（ApprovalReviewRequest）：**

```json
{
  "approved": true,
  "comment": "文档内容合规"
}
```

| 字段 | 必填 | 规则 |
|------|------|------|
| approved | ✅ | `true` 通过，`false` 拒绝 |
| comment | | 审批备注 |

**Response：** `200 OK`

> 通过后系统自动将文档状态从 `PENDING_APPROVAL` 改为 `PROCESSING`，并触发 ETL 处理。

---

### GET /api/teams/{teamId}/approvals/mine

我的上传审批状态（分页）。任何团队成员可查看自己上传文档的审批记录。

**Params：**

| 参数 | 必填 | 说明 |
|------|------|------|
| page | | 页码，默认 1 |
| size | | 每页条数，默认 20 |

**Response（PagedResult\<MyApprovalVO\>）：**

```json
{
  "content": [
    {
      "id": 1,
      "documentId": 42,
      "fileName": "report.pdf",
      "status": "APPROVED",
      "reviewerId": 1,
      "reviewComment": "文档内容合规",
      "createdAt": "2026-05-14T11:00:00+08:00",
      "reviewedAt": "2026-05-14T11:05:00+08:00"
    }
  ],
  "page": 1,
  "size": 20,
  "total": 1,
  "totalPages": 1
}
```

| 字段 | 说明 |
|------|------|
| documentId | 上传的文档 ID |
| fileName | 文档文件名 |
| status | `PENDING` / `APPROVED` / `REJECTED` |
| reviewedAt | 审批时间（未审批时为 null） |

## 团队分片上传

> 团队文档支持大文件分片上传，API 与个人分片上传平行，所有端点前缀为 `/api/teams/{teamId}/documents/multipart`。
> 需要登录。详细设计见 [分片上传设计文档](design/chunk-upload.md)。

### POST /api/teams/{teamId}/documents/multipart

创建团队文档分片上传会话。根据 `fileMd5` 自动判断秒传、新建或续传。

**Request：**

```json
{
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e",
  "fileName": "report.pdf",
  "fileSize": 52428800,
  "mimeType": "application/pdf",
  "totalChunks": 10
}
```

**Response（秒传 — 200）：**

```json
{
  "uploaded": true,
  "documentId": 42
}
```

**Response（新建 — 201）：**

```json
{
  "uploaded": false,
  "uploadId": "550e8400-e29b-41d4-a716-446655440000",
  "chunkSize": 5242880
}
```

**Response（续传 — 201）：**

```json
{
  "uploaded": false,
  "uploadId": "550e8400-e29b-41d4-a716-446655440000",
  "chunkSize": 5242880,
  "uploadedChunks": [0, 1, 2, 5]
}
```

---

### PUT /api/teams/{teamId}/documents/multipart/{uploadId}/chunks/{chunkIndex}

上传单个分片。最后一个分片上传完成后自动触发合并。

**Headers：**

| Header | 必填 | 说明 |
|--------|------|------|
| `X-Chunk-MD5` | ✅ | 分片 MD5（32 位 hex） |

**Request Body：** `application/octet-stream`

**Response（200）：**

```json
{
  "uploaded": true,
  "chunkIndex": 3,
  "autoMerged": false
}
```

---

### GET /api/teams/{teamId}/documents/multipart/{uploadId}

查询上传状态（断点续传）。

**Response（200）：**

```json
{
  "uploadId": "550e8400-e29b-41d4-a716-446655440000",
  "fileName": "report.pdf",
  "totalChunks": 10,
  "uploadedChunks": [0, 1, 2, 5, 6, 7],
  "chunkSize": 5242880
}
```

---

### POST /api/teams/{teamId}/documents/multipart/{uploadId}/complete

显式触发合并。

**Response（202 Accepted）：**

```json
{
  "documentId": 42,
  "status": "MERGING"
}
```

---

### DELETE /api/teams/{teamId}/documents/multipart/{uploadId}

取消上传，清理 Redis session 和 MinIO 临时分片。

**Response：** 204 No Content
