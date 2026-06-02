# Conversation 独立模块设计（v2 — 参考 DeepSeek 数据结构）

## 问题

1. 没有"会话"实体，conversationId 只是一个字符串 key
2. 没有独立的消息模型，完全依赖 Spring AI 的 `spring_ai_chat_memory`
3. 不支持消息树结构（重新生成、分支对话）
4. 不支持流式消息的状态追踪（IN_PROGRESS → FINISHED）

## 目标

参考 DeepSeek 的 `chat_session` + `chat_messages` 模型，建立两层结构：

- **Session（会话）**：用户的对话容器，有标题、置顶、状态等元数据
- **Message（消息）**：会话内的每一条 user/assistant 消息，有树结构、状态、fragment 分片

同时保持和 Spring AI 的兼容 — `spring_ai_chat_memory` 继续作为 Spring AI 读取多轮上下文的存储，
我们的 `message` 表作为业务层消息记录，两者通过 conversation_id 关联。

## 数据库设计（V5 迁移脚本）

### 表1：`conversation`（会话）

```sql
CREATE TABLE IF NOT EXISTS conversation (
    id              BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    conversation_id VARCHAR(100) NOT NULL,          -- 业务 ID: u_{userId}_{rawId}，关联 spring_ai_chat_memory
    user_id         BIGINT       NOT NULL,
    title           VARCHAR(200),
    title_source    VARCHAR(20)  DEFAULT 'SYSTEM',  -- SYSTEM=自动生成, USER=用户编辑
    model_id        VARCHAR(100),                    -- 创建时/最近使用的模型
    pinned          BOOLEAN      DEFAULT FALSE,      -- 置顶
    status          VARCHAR(20)  DEFAULT 'ACTIVE',   -- ACTIVE / ARCHIVED / DELETED
    message_count   INT          DEFAULT 0,          -- 冗余：活跃消息数
    last_message_at TIMESTAMPTZ,                     -- 冗余：最后一条消息时间
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_conversation_cid UNIQUE (conversation_id)
);

CREATE INDEX idx_conv_user_status ON conversation (user_id, status, last_message_at DESC);
```

### 表2：`message`（消息）

```sql
CREATE TABLE IF NOT EXISTS message (
    id                BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    conversation_id   VARCHAR(100) NOT NULL,          -- 关联 conversation
    parent_id         BIGINT,                          -- 父消息 ID（支持树结构/重新生成）
    role              VARCHAR(20)  NOT NULL,           -- USER / ASSISTANT / SYSTEM
    content           TEXT,                            -- 最终内容（流式完成后写入）
    status            VARCHAR(20)  DEFAULT 'FINISHED', -- FINISHED / IN_PROGRESS / ERROR
    model_id          VARCHAR(100),                    -- assistant 消息使用的模型
    thinking_enabled  BOOLEAN      DEFAULT FALSE,      -- 是否启用思考过程
    token_usage       INT,                            -- 该消息累计 token 消耗
    duration_ms       BIGINT,                          -- 响应耗时
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_message_parent FOREIGN KEY (parent_id) REFERENCES message(id) ON DELETE SET NULL
);

CREATE INDEX idx_msg_conv_id ON message (conversation_id, created_at ASC);
CREATE INDEX idx_msg_parent  ON message (parent_id);
```

### 设计说明

| DeepSeek 字段 | 我们的对应 | 说明 |
|---|---|---|
| `chat_session.id` (UUID) | `conversation.id` (BIGINT) + `conversation_id` (string) | 我们用自增主键 + 业务ID |
| `chat_session.title` / `title_type` | `title` / `title_source` | SYSTEM 自动取首条消息前 20 字，USER 手动编辑 |
| `chat_session.pinned` | `pinned` | 置顶 |
| `chat_session.model_type` | `model_id` | 记录使用的模型 |
| `chat_session.is_empty` | `message_count = 0` | 不单独存字段 |
| `chat_messages.message_id` | `message.id` | 自增主键 |
| `chat_messages.parent_id` | `message.parent_id` | 树结构：同一 parent 下多个子消息 = 重新生成分支 |
| `chat_messages.status` | `message.status` | IN_PROGRESS → FINISHED / ERROR |
| `chat_messages.accumulated_token_usage` | `message.token_usage` | 单条消息累计 token |
| `chat_messages.fragments` | 暂不实现 | V1 阶段不需要分片，流式完成直接写 content |

### 和 spring_ai_chat_memory 的关系

```
conversation (我们的)          spring_ai_chat_memory (Spring AI 管的)
┌─────────────────────┐        ┌─────────────────────────┐
│ conversation_id (PK)│◄──────►│ conversation_id (FK逻辑) │
│ title, status, ...  │        │ role, content, type      │
└─────────────────────┘        └─────────────────────────┘

message (我们的)               spring_ai_chat_memory
┌─────────────────────┐        ┌─────────────────────────┐
│ conversation_id     │        │ 同一个 conversation_id   │
│ parent_id (树结构)  │        │ 无树结构，线性消息列表    │
│ status, model_id    │        │ 无状态追踪               │
└─────────────────────┘        └─────────────────────────┘
```

- `spring_ai_chat_memory` 继续由 Spring AI 的 `MessageChatMemoryAdvisor` 读写
- 我们的 `message` 表是业务层记录，支持树结构和状态追踪
- 两者的 `conversation_id` 是同一个值，内容一致
- 后续如果不再依赖 Spring AI 的 memory 机制，可以完全迁移到 `message` 表

## 模块结构

```
com.demo.chat.conversation/
├── controller/
│   └── ConversationController.java      ← 从 chat 移过来 + 扩展 API
├── dto/
│   ├── ConversationCreateRequest.java    ← 新增：创建会话请求
│   ├── ConversationUpdateRequest.java    ← 新增：更新会话请求（标题/置顶/归档）
│   ├── ConversationSummary.java          ← 从 chat 移过来 + 扩展 title/pinned/modelId/status
│   ├── ConversationDetail.java           ← 新增：会话详情（含消息列表）
│   └── ConversationMessage.java          ← 从 chat 移过来 + 扩展 parentId/status/modelId
├── entity/
│   ├── Conversation.java                 ← 新增：会话实体
│   └── Message.java                      ← 新增：消息实体
├── mapper/
│   ├── ConversationMapper.java           ← 从 chat 移过来 + 重写
│   ├── ConversationMapper.xml            ← 从 chat 移过来 + 重写
│   ├── MessageMapper.java                ← 新增
│   └── MessageMapper.xml                 ← 新增
├── service/
│   ├── ConversationService.java          ← 从 chat 移过来 + 扩展
│   ├── ConversationQueryService.java     ← 新增：chat 模块查询接口
│   └── impl/
│       ├── ConversationServiceImpl.java  ← 从 chat 移过来 + 重写
│       └── ConversationQueryServiceImpl.java
├── enums/
│   ├── ConversationStatus.java           ← ACTIVE / ARCHIVED / DELETED
│   ├── MessageStatus.java                ← FINISHED / IN_PROGRESS / ERROR
│   └── TitleSource.java                  ← SYSTEM / USER
└── util/
    └── ConversationIdUtil.java           ← 从 chat 移过来
```

## 从 chat 模块迁出/删除的文件

| 文件 | 操作 |
|------|------|
| `chat.controller.ConversationController` | 移到 `conversation.controller` |
| `chat.service.ConversationService` | 移到 `conversation.service`，接口扩展 |
| `chat.service.impl.ConversationServiceImpl` | 移到 `conversation.service.impl`，重写 |
| `chat.dto.ConversationSummary` | 移到 `conversation.dto`，加字段 |
| `chat.dto.ConversationMessage` | 移到 `conversation.dto`，加字段 |
| `chat.mapper.ConversationMapper` + xml | 移到 `conversation.mapper`，重写 |
| `chat.util.ConversationIdUtil` | 移到 `conversation.util` |

## chat 模块需要的改动

### 1. ChatServiceImpl

- `prepareContext()` 中：调用 `ConversationQueryService.getOrCreate()` 确保会话存在
- 聊天完成后：更新 `message_count` 和 `last_message_at`
- 聊天完成后：写入 `message` 表记录（USER + ASSISTANT 各一条）

### 2. UsageController

- `ConversationIdUtil` 的 import 路径改为 `conversation.util`

### 3. CAG DefaultSessionContextResolver

- 可改为通过 `ConversationQueryService` 获取 `message_count`，避免查 memory

### 4. ConversationContextAdvisor / ChatAdvisorChainFactory / ChatRequestSpecFactory

- 不受影响，它们只用 conversationId 字符串

### 5. ChatResponse

- 保持 `conversationId` 字段不变

## 依赖方向

```
chat → conversation（chat 依赖 conversation 的查询接口）
conversation 独立于 chat、rag
conversation 可依赖 security（SecurityUtils）、common（snowflake）
```

## API 设计

```
POST   /api/conversations                     ← 显式创建会话（可选，默认自动创建）
GET    /api/conversations                     ← 列表（支持 ?status=ACTIVE&pinne=true）
GET    /api/conversations/{id}                ← 会话详情
GET    /api/conversations/{id}/messages       ← 消息列表（支持 ?parentId= 分支查询）
PUT    /api/conversations/{id}                ← 更新标题/置顶/归档
DELETE /api/conversations/{id}                ← 软删除（status=DELETED + 清空 memory）
POST   /api/conversations/{id}/messages/{msgId}/regenerate  ← 重新生成（V2 预留）
```

## 会话生命周期

1. **创建**：用户第一次发消息时自动创建（`getOrCreate`），或显式 POST 创建
2. **标题**：默认取第一条 USER 消息前 20 字符（title_source=SYSTEM），用户可 PUT 修改（title_source=USER）
3. **message_count**：每次写入消息时 +1
4. **last_message_at**：每次写入消息时更新
5. **归档**：PUT status=ARCHIVED，不在活跃列表显示
6. **删除**：DELETE → status=DELETED + 清空 spring_ai_chat_memory + 软删 conversation
7. **置顶**：PUT pinned=true，列表中置顶会话排在前面

## 消息生命周期

1. **USER 消息**：用户发消息时立即写入，status=FINISHED
2. **ASSISTANT 消息（阻塞式）**：响应完成后写入，status=FINISHED
3. **ASSISTANT 消息（流式）**：
   - 可选：开始时写入 status=IN_PROGRESS，完成后更新为 FINISHED
   - V1 简化：流式完成后再写入，始终 FINISHED
4. **重新生成**：新建一条同 parent_id 的 ASSISTANT 消息，形成分支

## token_usage 表的变更

现有 `token_usage` 表按 conversation_id + model_id 记录，按会话聚合查询。
新增 `message` 表后，每条消息也记录 `token_usage`。

两者关系：
- `token_usage` 表：保留，用于聚合统计（按模型、按时间段）
- `message.token_usage`：新增，用于单条消息级别的 token 追踪
- 数据来源相同，写入时两边都写

## 分步实施计划

- P1：建 V5 迁移脚本（conversation + message 两张表）
- P2：创建 conversation 模块目录结构 + enums
- P3：创建 Conversation、Message 实体
- P4：迁移 ConversationIdUtil 到 conversation.util
- P5：创建 ConversationMapper + MessageMapper（含 XML）
- P6：实现 ConversationService + ConversationQueryService
- P7：迁移并扩展 ConversationController
- P8：迁移 DTO（ConversationSummary、ConversationMessage）+ 新增 DTO
- P9：清理 chat 模块中的旧文件（删除已迁移的）
- P10：修改 ChatServiceImpl — 集成会话自动创建和消息写入
- P11：修改 UsageController — 切换 import 路径
- P12：修改 CAG DefaultSessionContextResolver — 用 message_count
- P13：数据迁移脚本（从 spring_ai_chat_memory 回填 conversation + message）
- P14：编译验证 + 测试 + commit push
