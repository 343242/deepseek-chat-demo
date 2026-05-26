# 消息处理与对话记忆机制优化审查

**日期**: 2026-05-27  
**审查范围**:

- `src/main/java/com/smart/rag/chat/**`
- `src/main/java/com/smart/rag/conversation/**`
- `src/main/java/com/smart/rag/config/*Memory*`
- `src/main/resources/db/migration/V5__conversation_and_message.sql`
- `src/main/resources/db/migration/V7__spring_ai_chat_memory.sql`

**审查口径**: 只读源码审查 + GitNexus 流程搜索 + Spring AI 官方文档核对  
**外部参考**:

- Spring AI Chat Memory: <https://docs.spring.io/spring-ai/reference/api/chat-memory.html>
- Spring AI Advisors: <https://docs.spring.io/spring-ai/reference/api/advisors.html>

**结论**: 当前架构方向正确，但存在若干可优化空间。`ChatMemory` 负责模型上下文窗口，`conversation/message` 表负责完整业务历史，这符合 Spring AI 官方对 chat memory 与 chat history 的职责区分。优先优化点集中在流式重试边界、窗口记忆与业务计数混用、同会话并发写、Redis memory 生命周期、长会话历史查询。

---

## 当前机制概览

### 1. 模型上下文记忆

`AdvisorAutoConfiguration` 使用 `MessageWindowChatMemory` 作为 `ChatMemory` 实现：

- `src/main/java/com/smart/rag/config/AdvisorAutoConfiguration.java:90`
- 默认窗口大小来自 `app.chat.memory.max-messages`，当前配置默认值为 20：
  - `src/main/resources/application.yml:14`

`ChatAdvisorChainFactory` 在多轮和 Agent 模式下挂载 `MessageChatMemoryAdvisor`：

- `src/main/java/com/smart/rag/chat/service/ChatAdvisorChainFactory.java:159`
- `src/main/java/com/smart/rag/chat/service/ChatAdvisorChainFactory.java:241`

`ConversationContextAdvisor` 和请求 spec 都通过 `ChatMemory.CONVERSATION_ID` 传递会话维度：

- `src/main/java/com/smart/rag/chat/advisor/ConversationContextAdvisor.java:44`
- `src/main/java/com/smart/rag/chat/service/ChatRequestSpecFactory.java:75`
- `src/main/java/com/smart/rag/chat/service/impl/ChatServiceImpl.java:216`

### 2. Redis ChatMemoryRepository

`RedisChatMemoryAutoConfiguration` 提供 Redis-backed `ChatMemoryRepository`：

- `src/main/java/com/smart/rag/config/RedisChatMemoryAutoConfiguration.java:20`

`RedisChatMemoryRepository` 使用每个 conversation 一个 Redis sorted set：

- key: `chat:memory:{conversationId}`
- score: timestamp
- member: message JSON

相关实现：

- `src/main/java/com/smart/rag/chat/memory/RedisChatMemoryRepository.java:81`
- `src/main/java/com/smart/rag/chat/memory/RedisChatMemoryRepository.java:112`
- `src/main/java/com/smart/rag/chat/memory/RedisChatMemoryRepository.java:142`

### 3. 业务完整历史

业务层使用 `conversation` 和 `message` 表保存完整历史：

- `src/main/resources/db/migration/V5__conversation_and_message.sql:15`
- `src/main/resources/db/migration/V5__conversation_and_message.sql:36`

`ChatConversationHelper.saveMessagesAndNotify()` 在模型调用后保存业务消息，并递增会话计数：

- `src/main/java/com/smart/rag/chat/service/ChatConversationHelper.java:94`
- `src/main/java/com/smart/rag/conversation/service/impl/ConversationServiceImpl.java:182`

这形成了当前的“双轨机制”：

| 轨道 | 数据源 | 用途 |
|------|--------|------|
| 模型短期记忆 | Redis `ChatMemoryRepository` + `MessageWindowChatMemory` | 下轮模型上下文 |
| 业务完整历史 | PostgreSQL `conversation` / `message` | 前端历史、消息树、会话列表、审计 |

---

## 优化发现

### H1: 流式重试可能把失败片段写入 ChatMemory

**文件**:

- `src/main/java/com/smart/rag/chat/service/impl/ChatServiceImpl.java:313`
- `src/main/java/com/smart/rag/chat/fallback/StreamRetryHandler.java:68`
- `src/main/java/com/smart/rag/chat/service/ChatConversationHelper.java:131`

**问题**:

`doStream()` 在每次底层流结束时执行 `doFinally()`。当信号为 `ON_ERROR` 或 `CANCEL` 时，代码会调用 `savePartialResponse()`，把已收集的部分 assistant 内容写入 `ChatMemory`。

但外层 `StreamRetryHandler` 会在可降级异常后继续同模型重试或切换模型。也就是说，一次失败尝试产生的半截回复可能先进入模型记忆；随后重试成功，又写入完整回复。

**风险**:

- 下一轮模型上下文可能同时包含失败片段和最终回复。
- 用户看不到的失败片段可能影响后续回答。
- 多次重试时，ChatMemory 中可能积累多个 partial assistant message。

**建议**:

1. 普通可重试错误不要写入 ChatMemory。
2. 只有整个重试/降级链最终失败，或客户端主动取消时，才考虑保存 partial。
3. partial 更适合写入业务 `message` 表，并显式标记 `status=PARTIAL` / `CANCELLED`，不要混入模型上下文。

---

### H2: CAG messageCount 使用窗口记忆数量，长对话阶段判断会失真

**文件**:

- `src/main/java/com/smart/rag/chat/service/impl/ChatServiceImpl.java:365`
- `src/main/java/com/smart/rag/chat/service/ChatConversationHelper.java:52`
- `src/main/java/com/smart/rag/config/AdvisorAutoConfiguration.java:90`
- `src/main/resources/application.yml:14`

**问题**:

`buildCagContext()` 通过 `conversationHelper.getMessageCount()` 获取会话消息数，而该方法读取的是 `chatMemory.get(conversationId).size()`。当前 `ChatMemory` 是 `MessageWindowChatMemory`，默认只保留 20 条。

**风险**:

- 长会话在 CAG 里可能被误判为短会话。
- `SessionContextResolver`、策略约束或提示词增强若依赖 messageCount，会受到窗口裁剪影响。
- 业务 `conversation.message_count` 已经记录完整计数，但当前 CAG 没有使用它。

**建议**:

将 CAG 的 messageCount 来源改为业务完整历史：

- 优先读取 `conversation.message_count`。
- 或通过 `ConversationMessageService.getMessageCount()` 读取 `message` 表。
- 保留 `ChatMemory` 只作为模型上下文窗口，不承担完整历史统计职责。

---

### H3: 同一 conversationId 并发请求可能互相覆盖 Redis ChatMemory

**文件**:

- `src/main/java/com/smart/rag/chat/memory/RedisChatMemoryRepository.java:142`
- `src/main/java/com/smart/rag/chat/memory/RedisChatMemoryRepository.java:162`

**问题**:

`RedisChatMemoryRepository.saveAll()` 使用 `DEL key` + 批量 `ZADD` 替换整个会话窗口。若同一 conversationId 同时发起两个请求，两个请求都可能基于旧窗口生成新窗口，后完成者会覆盖先完成者。

**风险**:

- 丢失某一轮对话的短期记忆。
- 业务 `message` 表可能有完整历史，但模型下轮上下文缺失某些消息。
- 流式请求时间更长，并发覆盖窗口更大。

**建议**:

1. 在 chat service 入口按 `conversationId` 做轻量串行化。
2. 或在 Redis 层引入版本号 / CAS 写入。
3. 对同一会话的第二个并发请求，可返回“上一轮仍在生成”或排队执行。

---

### M1: Redis ChatMemoryRepository 支持 TTL，但当前未配置

**文件**:

- `src/main/java/com/smart/rag/config/RedisChatMemoryAutoConfiguration.java:20`
- `src/main/java/com/smart/rag/chat/memory/RedisChatMemoryRepository.java:55`
- `src/main/java/com/smart/rag/chat/memory/RedisChatMemoryRepository.java:172`

**问题**:

`RedisChatMemoryRepository` 已支持 `ttlSeconds`，但自动配置没有传入 TTL，默认值为 `-1`，表示不过期。

业务完整历史已经存入 PostgreSQL `message` 表，因此 Redis ChatMemory 更像模型上下文热缓存，不需要永久保存。

**风险**:

- Redis 中长期累积冷会话 memory。
- 归档但未删除的会话仍占用 Redis。
- 运维侧难以基于业务生命周期控制 memory 数据。

**建议**:

新增配置：

```yaml
app:
  chat:
    memory:
      max-messages: 20
      ttl-seconds: 2592000 # 30 天
```

并在 `RedisChatMemoryAutoConfiguration` 中传给 builder。删除会话时继续显式清理 memory。

---

### M2: 业务消息详情全量加载，长会话会变重

**文件**:

- `src/main/java/com/smart/rag/conversation/service/impl/ConversationMessageServiceImpl.java:38`
- `src/main/java/com/smart/rag/conversation/mapper/MessageMapper.java:20`
- `src/main/java/com/smart/rag/conversation/service/impl/ConversationServiceImpl.java:129`

**问题**:

`getDetail()` / `listMessages()` 会调用 `buildMessageTree()`，后者一次性查询会话下所有消息并在内存中分组组树。

**风险**:

- 长会话详情页响应越来越慢。
- Agent 模式、多轮工具调用、长文本回答会加快消息体增长。
- 前端通常不需要一次加载完整历史，尤其移动端和长会话。

**建议**:

1. 会话详情默认返回最近 N 轮。
2. 历史消息使用 cursor 分页，例如按 `created_at` / `id` 向前翻页。
3. 分支/子消息按 parent 懒加载。
4. 保留“导出完整会话”作为单独接口，不走普通详情接口。

---

### M3: 删除消息逐条 delete，可改为按 conversation_id 批量删除

**文件**:

- `src/main/java/com/smart/rag/conversation/service/impl/ConversationMessageServiceImpl.java:57`
- `src/main/java/com/smart/rag/conversation/mapper/MessageMapper.java:20`

**问题**:

删除会话时先查询全部消息，再逐条 `deleteById()`。

**风险**:

- 长会话删除时产生大量 SQL。
- 删除事务持有时间变长。

**建议**:

增加 mapper 方法：

```sql
DELETE FROM message WHERE conversation_id = #{conversationId}
```

如果未来引入软删除，则改为批量 `UPDATE status = 'DELETED'`。

---

### M4: ChatMemory 与业务 message 表缺少补偿机制

**文件**:

- `src/main/java/com/smart/rag/chat/service/ChatConversationHelper.java:94`
- `src/main/java/com/smart/rag/chat/service/ChatConversationHelper.java:118`
- `src/main/java/com/smart/rag/chat/service/impl/ChatServiceImpl.java:197`
- `src/main/java/com/smart/rag/chat/service/impl/ChatServiceImpl.java:324`

**问题**:

当前模型调用成功后，`MessageChatMemoryAdvisor` 负责写 ChatMemory，`ChatConversationHelper` 另写业务 message 表。如果业务写失败，代码只记录日志，不影响已返回给用户的响应。

**风险**:

- 模型下轮“记得”，但前端历史不显示。
- 业务会话 `message_count` 与真实 ChatMemory 窗口不一致。
- 事故排查时缺少补偿记录。

**建议**:

1. 建立 outbox / retry 表记录业务消息保存失败。
2. 后台任务补偿写入 `message` 表或标记会话异常。
3. 或将业务 message 表作为事实源，再投影到 ChatMemory，减少双写不一致。

---

### M5: Agent 工具过程不宜进入 ChatMemory，但应沉淀短摘要

**文件**:

- `src/main/java/com/smart/rag/chat/service/ChatAdvisorChainFactory.java:219`
- `src/main/java/com/smart/rag/rag/agent/event/AgentEventStore.java`
- `src/main/java/com/smart/rag/rag/agent/workspace/ToolWorkspace.java`

**问题**:

Spring AI 官方文档说明工具调用过程中的中间消息当前不会自动写入 memory。项目已有 `AgentEventStore` 存 agent 事件，这是正确方向。但如果只保存最终 assistant 回复，下轮模型无法稳定获知上一轮 Agent 使用了哪些工具、检索了哪些文档、是否发生降级。

**建议**:

每次 Agent 回答后生成一条短摘要，写入 agent event 或业务 message metadata：

- intent
- 使用过的 tool 名称
- 关键 docId / parentId
- 是否触发 guardrail / degradation
- 检索轮次与耗时

下轮需要时通过 `agentEventLookup` 或 CAG 注入摘要，不要把完整工具输出塞进 ChatMemory。

---

## 保留现状的部分

以下设计不建议推倒重做：

1. **`ChatMemory` 与业务历史分离**  
   这符合 Spring AI 官方建议。`ChatMemory` 不应承担完整历史存储职责。

2. **使用 `MessageWindowChatMemory` 控制模型上下文窗口**  
   该机制适合控制 token 和上下文规模，当前默认 20 条是合理起点。

3. **使用 `ConversationContextAdvisor` 注入 conversationId**  
   该做法让 `RateLimitAdvisor`、`MessageChatMemoryAdvisor` 等通过统一 context 获取会话维度，方向正确。

4. **Agent 工具过程不直接进入 ChatMemory**  
   工具调用过程更适合进入事件/trace，而不是污染短期对话上下文。

---

## 优先级建议

### P0

1. 修正流式重试时 partial response 写入 ChatMemory 的边界。
2. 将 CAG messageCount 改为读取业务完整计数。
3. 为同一 conversationId 的并发请求增加串行化或 Redis CAS。

### P1

1. 给 Redis ChatMemory 增加 TTL 配置。
2. 会话详情和消息列表增加分页/游标。
3. 删除会话消息改为批量 delete / update。

### P2

1. 增加 ChatMemory 与业务 message 表的补偿机制。
2. 为 Agent 回合沉淀短摘要，服务后续恢复与检索。

---

## 建议测试

1. 流式第一次失败、第二次重试成功时，ChatMemory 中不包含第一次失败的 partial assistant message。
2. 长会话超过 `app.chat.memory.max-messages` 后，CAG messageCount 仍等于业务完整消息数。
3. 同一 conversationId 并发两次发送时，ChatMemory 不丢任一轮成功消息。
4. Redis memory TTL 配置生效，`saveAll()` 后 key 有过期时间。
5. `GET /api/conversations/{id}` 支持分页后，长会话不会一次性返回全部消息。

---

## 复核状态

- 本报告为只读分析结果沉淀。
- 未修改生产代码。
- 未运行测试；本报告不声明任何问题已修复。
