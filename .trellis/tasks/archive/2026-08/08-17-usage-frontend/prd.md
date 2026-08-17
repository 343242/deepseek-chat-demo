# PRD — 每轮对话 Token/耗时显示 + 用量统计前端页

## Goal

让每轮对话在聊天气泡实时显示真实 tokenUsage 与 durationMs（流式当场可见、历史消息有值），并交付用量统计前端页（时间桶图表 + 汇总 + 分组聚合 + 明细分页 + 管理员维度）。

## 痛点

1. 流式落库 aiResponse 传 null → message.token_usage 恒 -1（与 usage 模块改造前同款病根）。
2. ChatMessagePublisher 的 elapsedMs 参数被丢弃，payload 不携带 duration，consumer 硬编码 0L → duration_ms 恒 0。
3. SSE 尾帧不带 usage → 流式当场看不到 token/耗时，只能等异步落库后的历史刷新（有竞态）。
4. usage-page 为占位页。

## Requirements

- message 表 token_usage/duration_ms：阻塞/流式均落真实值；-1 哨兵废除，NULL 表未知。
- SSE 新增 event:usage 尾帧（tokenUsage+durationMs），仅成功流发送；每轮显示只给真实值不估算。
- 前端：usage 帧接入 stream-reducer/chat-store；气泡渲染零改动（已有渲染位）。
- 用量页：摘要卡/时间桶图(day|month+场景+预设范围)/分组聚合表(dim=model|scene|user，表头排序)/明细分页表；USAGE_VIEW_ALL 控制 userId 过滤与用户排行。
- 路由：usage 包 PermissionGuard(usage:view)。

## Acceptance Criteria

- [ ] 流式完成后气泡立即显示 token 与耗时（无需刷新）；历史消息显示落库值
- [ ] mvn test 全绿（含 SseStreamBridge usage 帧用例）；vitest/tsc/build 全绿
- [ ] detect_changes 影响面符合预期

## 非目标

REWRITE/embedding 采集、cacheHitTokens、自定义日期区间选择器。
