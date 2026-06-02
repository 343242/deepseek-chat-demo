# 复盘：conversationId 自动生成 UUIDv7

## 问题

前端不传 conversationId 时，ChatRequest 兜底为 `"default"`，导致所有未指定 ID 的请求共享同一个会话 `u_1_default`。这与 conversation 模块的 UUIDv7 设计矛盾——每个会话应有独立的全局唯一 ID。

## 修复

| 文件 | 改动 |
|------|------|
| ChatRequest | `conversationId()` 返回 null 而非 "default" |
| ChatServiceImpl | `prepareContext()` 检测 null 时调用 `UuidV7.generateCompact()` |
| ChatContext | 新增 `rawConversationId` 字段，传递给 ChatResponse |
| ChatController | SSE 的 conversationId 参数改为 `required=false` |

## 验证结果

```
不传 conversationId → 返回 "019e1c840edb7a1611bb47ddb9aa3581" (UUIDv7)
传指定 ID           → 返回 "my-chat-001" (使用传入值)
```

## 设计决策

- **返回 rawConversationId 而非隔离后的 ID**：前端只需保存原始 ID，隔离前缀由后端处理
- **前端用返回的 conversationId 继续后续请求**：MULTI_TURN 模式下前端必须保存首次返回的 ID

## 教训

1. **兜底值不应该是业务标识**："default" 作为 fallback 看似方便，实际上让多个不同对话混在一起
2. **ID 生成应该在服务端**：前端不应该负责生成会话 ID，后端自动生成更可靠
3. **先设计再实现**：conversation 模块设计了 UUIDv7，但 ChatRequest 还在用旧的 default 兜底，是模块化重构不彻底的表现
