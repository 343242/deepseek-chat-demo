# PRD: 性能优化 — 启动时间 + 关键链路响应速度

## 目标
- 启动时间：5.8s → < 4s
- 关键链路（chat/stream）每次请求的框架开销最小化

## 优化项

### P1 — 启动优化

#### OPT-1: ModelRegistryRefresher 并行拉取模型
**问题：** 3 个 Provider 串行 HTTP 拉取模型列表，总耗时 = sum(各 Provider 耗时)
**方案：** 用 CompletableFuture 并行拉取模型列表，串行创建 ChatClient（创建很快）
**预期收益：** ~400-500ms

#### OPT-2: ChatClient 延迟创建
**问题：** 启动时一次性创建 42 个 ChatClient（每个都要初始化 HTTP Client、配置选项）
**方案：** 改为按需创建 + 缓存（首次使用时创建，之后复用）
**预期收益：** 启动快 ~200-300ms，首次请求略慢

### P2 — 运行时优化

#### OPT-3: ChatAdvisorChainFactory 缓存基本链
**问题：** 每次请求都新建 ArrayList + 多次 ObjectProvider 解析
**方案：** 缓存不含会话相关 Advisor 的基础链（globalAdvisors + toolCallAdvisor），只动态添加 ConversationContext/Memory
**预期收益：** 每次请求减少 ~0.1ms（微小但有意义）

#### OPT-4: ToolRegistry 状态缓存
**问题：** `hasTools()` 和 `getToolCallbacks()` 每次都走 ObjectProvider
**方案：** ToolRegistry 在构造时已经确定工具列表，ChatAdvisorChainFactory 直接引用 ToolRegistry 而非每次 ObjectProvider
**预期收益：** 消除不必要的 ObjectProvider 间接调用

#### OPT-5: RagAdvisorFactory 查询转换器缓存
**问题：** 每次 RAG 请求创建新的 QueryTransformer（如果有）
**方案：** 确认 QueryTransformer 是无状态的，可以复用单例

## 不优化
- `resolveSystemPrompt()` — 已有 Caffeine + Redis + DB 三级缓存，足够好
- `resolveChatOptions()` — 已有 Caffeine 30s 缓存
- `SecurityUtils` — 开销极小
