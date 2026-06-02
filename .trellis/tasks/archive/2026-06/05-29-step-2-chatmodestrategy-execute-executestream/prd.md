# Step 2: ChatModeStrategy 执行下沉 — execute/executeStream 多态分发

## 目标

消除 `ChatServiceImpl` 中的 `isAgentMode()` 分支，将执行逻辑下沉到策略模式。
设计文档：`docs/design/chat-mode-strategy-step2-execute-sinking.md`

## 范围

### Phase A — execute() 下沉 + StrategyExecuteResult

1. 创建 `StrategyExecutionContext` record
2. 创建 `StrategyExecuteResult` record
3. `ChatModeStrategy` 新增 `execute()` / `executeStream()` default 方法
4. `ChatRequestSpecFactory.createSpec()` 简化签名（移除 strategy 参数，接收 `List<Advisor> chain`）
5. `SimpleModeStrategy` 实现 execute
6. `MultiTurnModeStrategy` 实现 execute
7. `AgentModeStrategy` 实现 execute（含降级）
8. `ChatServiceImpl.doChat()` 改为 `strategy.execute()` + `processResult()`
9. `ModeChainResult` 移除 skipXxx 字段
10. 编译 + 功能验证

### Phase B — 标准流式下沉

1. `SimpleModeStrategy` 实现 executeStream
2. `MultiTurnModeStrategy` 实现 executeStream
3. `ChatServiceImpl.chatStream()` / `doStream()` 改为 `strategy.executeStream()`
4. 编译 + 标准模式流式验证

### Phase D — 清理

1. 删除 `isAgentMode()` / `isMemoryEnabled()` flag 方法
2. 删除 `doAgentChat()` / `doStandardFallbackChat()`
3. `ChatAdvisorChainFactory` 去留决策（按引用情况）
4. 编译 + 全量回归

## 关键约束

- `FallbackEligibility` 将 `BusinessException` 视为不可降级 — Agent 流式抛此异常
- `AgentModeStrategy` 用 `ObjectProvider<MultiTurnModeStrategy>` 避免循环依赖
- `processResult()` 使用 `rawConversationId`（返回客户端）和 `conversationId`（内部使用）
- 流式 usage 记录在 `doFinally` 统一处理，`onStreamComplete()` 仅负责消息持久化

## 改动文件

| 文件 | 动作 |
|------|------|
| `chat/service/StrategyExecuteResult.java` | 新增 |
| `chat/service/StrategyExecutionContext.java` | 新增 |
| `chat/mode/ChatModeStrategy.java` | 修改 |
| `chat/mode/SimpleModeStrategy.java` | 修改 |
| `chat/mode/MultiTurnModeStrategy.java` | 修改 |
| `rag/agent/mode/AgentModeStrategy.java` | 修改 |
| `chat/service/ModeChainResult.java` | 精简 |
| `chat/service/ChatRequestSpecFactory.java` | 简化 |
| `chat/service/impl/ChatServiceImpl.java` | 简化 |
