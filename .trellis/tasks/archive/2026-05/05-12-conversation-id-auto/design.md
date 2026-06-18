# Task: conversationId 自动生成 — 去除 "default" 兜底

## 问题

前端不传 conversationId 时，ChatRequest 兜底为 `"default"`，导致所有未指定 ID 的请求共享同一个会话 `u_1_default`，违背每会话独立 UUIDv7 的设计。

## 修复计划

- [1] ChatRequest: conversationId null 时不兜底 "default"
- [2] ChatServiceImpl: 空 conversationId 时自动生成 UUIDv7
- [3] ChatController SSE: 去掉 defaultValue="default"
- [4] 编译 + 测试
- [5] 启动验证
- [6] Git commit + push
