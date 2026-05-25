# 会话与 ID 设计

> 雪花 ID 生成器、UUIDv7、Conversation 模块的详细设计。

## 自研雪花 ID（仅 SysUser）

```
┌─────────────────────────────────────────────────────────────────┐
│ 0 (1b) │ timestamp (41b) │ datacenterId (5b) │ workerId (5b) │ seq (12b) │
└─────────────────────────────────────────────────────────────────┘
```

| 特性 | 说明 |
|------|------|
| 自定义纪元 | 默认 2026-01-01，可用约 69 年 |
| datacenterId + workerId | 10 位，最多 1024 实例 |
| 时钟回拨容忍 | ≤5ms 等待恢复 |
| 线程安全 | ReentrantLock |
| 吞吐量 | 每秒 409.6 万 |

## Conversation 模块（独立于 chat）

```
┌─────────────────── chat 模块 ───────────────────┐
│                                                 │
│  ChatServiceImpl                                │
│    │                                            │
│    ├─ ensureConversationExists() ──────────────┐│
│    │   (getOrCreate, 并发安全)                  ││
│    │                                           ││
│    ├─ saveMessagesAndNotify() ────────────────┐││
│    │   TransactionTemplate:                    │││
│    │     1. saveMessage(USER)                  │││
│    │     2. saveMessage(ASSISTANT)             │││
│    │     3. onNewMessages(count+title)         │││
│    │                                           ▼▼▼
│    │                              ┌── conversation 模块 ──┐
│    │                              │ ConversationService   │
│    │                              │   ├─ create (UUIDv7)  │
│    │                              │   ├─ getOrCreate      │
│    │                              │   ├─ update/delete    │
│    │                              │   └─ onNewMessages    │
│    │                              │                      │
│    │                              │ ConversationMsgSvc   │
│    │                              │   ├─ buildMsgTree    │
│    │                              │   │  (全量查+内存分组) │
│    │                              │   ├─ saveMessage      │
│    │                              │   └─ deleteByConvId   │
│    │                              └──────────────────────┘
│    │
│    └─ Spring AI ChatMemory ──→ spring_ai_chat_memory 表
│                               (独立于 message 表)
└─────────────────────────────────────────────────────────┘
```

### 双写架构

| 层 | 存储 | 用途 |
|----|------|------|
| Spring AI | `spring_ai_chat_memory` | 多轮对话历史，框架自动管理 |
| 业务层 | `message` 表 | 消息树（分支/重新生成）+ Token 用量 + 耗时 |
| 业务层 | `conversation` 表 | 会话元数据（标题/置顶/状态/计数） |

### 关键设计

- **conversationId = `u_{userId}_{uuidv7}`**：用户隔离 + 时间有序
- **UUIDv7 (RFC 9562)**：48 位毫秒时间戳 + 74 位随机数，自实现零依赖
- **消息树**：`parent_id` 构建树形，一次全量查 + 内存 `groupingBy(parentId)` 分组（禁止 N+1）
- **并发安全**：`getOrCreate` 依赖唯一约束 + catch `DuplicateKeyException` 重查
- **编程式事务**：`TransactionTemplate` 保证 USER+ASSISTANT 消息原子写入
- **CAS 标题**：首次消息时原子设置标题（`WHERE message_count = 0 AND title_source = 'SYSTEM'`）
- **枚举映射**：`@EnumValue` + `@JsonValue`，实体用枚举不用 String

### 依赖方向

```
chat → conversation → (security, common)
  ↑ 独立       ↑ 独立于 rag、user
```
