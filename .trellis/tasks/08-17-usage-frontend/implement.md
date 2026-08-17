# Implement

1. [x] 后端数据链路（extract/payload/publisher/consumer/Message/helper 签名）
2. [x] 流式捕获（两策略 + StreamResult.usageRef + StreamCompletionHelper token 参数）
3. [x] SSE event:usage（SseTailFrames + bridge + ChatServiceImpl 传 ref）+ ChatResponse DTO 对齐
4. [x] 后端测试更新 + mvn test
5. [x] 前端 usage 帧接线（types/sse/stream-reducer/chat-store）
6. [x] api/usage.ts + timeline-chart + usage-page + App.tsx 守卫
7. [x] vitest + tsc + build + detect_changes
