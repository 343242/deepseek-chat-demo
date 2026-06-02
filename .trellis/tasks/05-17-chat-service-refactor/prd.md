# PRD — ChatServiceImpl 拆分重构

## 背景

`ChatServiceImpl`（449 行）是 chat-demo 的核心编排类，承载了阻塞式聊天、流式聊天、单次调用核心、对话持久化四大职责。当前 12 个构造器依赖，5 个 public/private 方法，职责过重。

## 现状分析

```
ChatServiceImpl (449 行)
├── 阻塞式聊天 (L108-155, ~47 行)
│   └── chat() → fallback 链 + doChat()
├── 流式聊天 (L156-180, ~24 行)
│   └── chatStream() → fallback 链 + doStream()
├── 单次调用核心 (L181-279, ~98 行)
│   ├── doChat() → 构建 context → 调用 → 保存 → 计量
│   └── doStream() → 构建 context → 流式调用 → 收集 → 保存
└── 内部辅助 (L280-449, ~169 行)
    ├── buildCagContext()     — 上下文构建
    ├── ensureConversationExists() — 会话管理
    ├── saveMessagesAndNotify()    — 消息持久化
    ├── savePartialResponse()      — 部分响应保存
    └── recordUsage()             — 用量计量
```

## 拆分方案

### 新增类

| 类名 | 职责 | 来源 |
|------|------|------|
| `ChatConversationHelper` | 会话管理 + 消息持久化 | `ensureConversationExists()` + `saveMessagesAndNotify()` + `savePartialResponse()` |
| `ChatUsageTracker` | 用量计量 | `recordUsage()` |

### 拆分后 ChatServiceImpl

```
ChatServiceImpl (~250 行)
├── chat()       → 调用 doChat() + fallback
├── chatStream() → 调用 doStream() + fallback
├── doChat()     → 构建 context → 调用 → 委托 ChatConversationHelper 保存
├── doStream()   → 构建 context → 流式调用 → 委托 ChatConversationHelper 保存
└── buildCagContext() — 留在原处（与 chat 上下文强相关）
```

### 依赖变化

```
ChatServiceImpl（重构后）:
  - ChatClientRegistry registry
  - ModelRouter modelRouter
  - ModeRouter modeRouter
  - ChatRequestSpecFactory requestSpecFactory
  - ChatFallbackProperties fallbackProperties
  - FallbackChainProvider fallbackChainProvider
  - FallbackEligibility fallbackEligibility
  - StreamRetryHandler streamRetryHandler
  - RequestContextManager cagContextManager
  - CagProperties cagProperties
  - ChatConversationHelper conversationHelper  ← 新
  - ChatUsageTracker usageTracker              ← 新

ChatConversationHelper:
  - ConversationService
  - ConversationMessageService
  - TransactionTemplate
  - ChatMemory

ChatUsageTracker:
  - UsageService
```

## Phase 划分

### Phase 1: 提取 ChatUsageTracker

1. 新建 `ChatUsageTracker`，从 `ChatServiceImpl` 提取 `recordUsage()` 方法
2. ChatServiceImpl 注入 ChatUsageTracker，委托调用
3. 确保编译通过 + 现有测试不挂

### Phase 2: 提取 ChatConversationHelper

1. 新建 `ChatConversationHelper`，提取以下方法：
   - `ensureConversationExists()`
   - `saveMessagesAndNotify()`
   - `savePartialResponse()`
2. ChatServiceImpl 注入 ChatConversationHelper，委托调用
3. 移除 ChatServiceImpl 中不再直接需要的依赖（ConversationService、ConversationMessageService、TransactionTemplate、ChatMemory）

### Phase 3: 清理 + 验证

1. ChatServiceImpl 最终审查：确认行数 < 300，职责清晰
2. 检查新类的包归属（`chat.service.helper` 或 `chat.service`）
3. 补充新类的单元测试
4. 全量编译 + 测试通过

## 约束

- 行为不变：纯重构，外部 API 不变
- 每个 Phase 独立 commit
- 编译 + 全量测试通过后才 commit
- 不 push
