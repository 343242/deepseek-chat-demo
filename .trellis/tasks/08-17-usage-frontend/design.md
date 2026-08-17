# Design — 数据源与断链修复

token 数据链路：厂商 SSE JSON → GenericChatClient.parseTokenUsage（SPI 自有）→ ChatModelAdapter 注入 DefaultUsage → ChatModel 边界读 Spring AI Usage（全链路唯一咽喉，与 usage_event 同口径）。

- extractTotalTokens → @Nullable Integer；Message.assistantMessage/saveMessagesAndNotify tokenUsage 参数 nullable Integer。
- ChatMessagePayload：totalTokens @Nullable Long + durationMs；publisher 唯一签名 (conv,user,content,candidate,@Nullable Integer,long)，删 ChatResponse 重载。
- 流式捕获：两策略 .chatResponse() 后 doOnNext 累计轮末真实 usage（静态 helper，splitIntoFrames 先例）；StreamResult 增 usageRef（AtomicReference<StreamUsageSnapshot{tokenUsage,durationMs}>），doOnComplete 写入（先于桥接层 onComplete——chat-stream-cancel 设计已背书的 reactor 语义）。
- SseTailFrames 增 usageRef；complete() 在 references 前发 event:usage（无值跳过）。
- 阻塞 chat/dto/ChatResponse 增 @Nullable tokenUsage/durationMs。
- 前端：SseFrame usage 帧 + onUsage + applyFrame 分支 + store 接线。
- 用量页：api/usage.ts(TanStack Query 惯例) + React.lazy(echarts-for-react)（manualChunks 已拆包）+ PageContainer 布局。
