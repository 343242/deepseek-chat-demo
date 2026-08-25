# LLM 模块弹性与传输层重构设计

> 状态：**设计定稿，待实施**（2026-08-25，v1.2 评审修订）
> 范围：`src/main/java/com/smart/rag/infrastructure/llm/` 模块 + `chat`/`mode` 层的流式降级协议 + application.yml 弹性配置
> **前置依赖：`docs/design/llm-client-stateless.md`（客户端无状态化 + BYOK 重构，WS0）必须先于本方案 WS1–WS8 完成**
> 关联文档：`docs/design/bailian-sdk-integration.md`（SDK 化决策 6 的 response_format 双路径互补）；`docs/design/llm-client-stateless.md`（前置架构矫正，含 pi-ai 调研摘要）
> ⚠️ **定位更新（2026-08-26）**：前置文档已定稿为 v3.x「协议无状态化 + **BYOK 砍除**（仅系统模型）」——本文中按 BYOK 前提表述的条目（WS0 五工作流 WS-A~E、决策 13/14、WS4 前提等）随移除自然成立或失去对象，接口级结论不变（WS-A 仍先于 WS1–WS3）；正文待下次修订同步，实施以前置文档 v3.2 为准。

**修订记录**：
- v1.2（2026-08-25）：二轮评审 + 架构前置决策（用户确认）：
  - **新增 WS0 前置工作流**：客户端无状态化 + 调用时凭据解析（pi-ai 模式），消除 BYOK per-user 客户端对象图及全部连锁债务（P10 入问题清单）；**必须在 WS1–WS8 之前完成**。BYOK 弹性降格为 retry-only + 降级链。**（v1.2 定稿后独立成文：详设见 `docs/design/llm-client-stateless.md`，本文仅保留 P10/决策 13/14 与接口级依赖说明）**
  - **WS4**：BYOK 流量不进 per-candidate 闸门/指标（经 WS0 路径天然分离）；流式 acquire 改**非阻塞轮询**（消除 boundedElastic 任务队列排队盲区，排队时间计入 acquire-timeout 预算）；release 增加 acquired/released **双标志语义**（acquire 失败路径绝不释放）；注册表复用判定比较**整个 ConcurrencyConfig**；`refresh()` 移除候选路径挂接 evict（顺带修复熔断器注册表既有缺口）。
  - **WS1**：Retry-After **尊重服务端**——≤60s 原样等待（不与 maxDelayMs 耦合、不叠 jitter），>60s 视为服务端明示长时间限流、不可同模型重试直接降级（放弃阈值语义取代截断语义）；补 HTTP-date 解析；P4 措辞修正（流式路径现状已有 Reactor 默认 jitter，无 jitter 的仅阻塞路径）。
  - **WS5**：`stripUserPrefix` public 化**取消**（WS0 后 candidateId 天然无前缀）；`emittedAny` 保持含 reasoning 帧置位并补理由。
  - **WS7**：cache_hit 补**流式接线**（轮末汇总包打点，覆盖流式主路径）；TTFT 起点改订阅时捕获。
  - **WS6**：非白名单键告警改**每键首见 WARN**（防逐请求刷屏）。
  - 低危修正：§2 camelCase 绑定语义纠正（Map 键原样保留，非静默丢弃）；WS3 补明文 h2c 行为差异记录；WS2 补跨模型全链最坏上界说明；§10 移除已由 WS0 消解的 fallback candidateId 归一化项。
- v1.1（2026-08-25）：纳入评审意见 H1/H2/M1-M5/L1-L5——闸门初值上调并增加标定与灰度流程（H1）；新增 AdmissionControlRegistry 与 gauge 生命周期管理（H2）；明确双共享 OkHttp 实例结构（M1，用户确认理解：统一指技术栈统一，阻塞/流式各持共享实例而非单实例两用）；公平 Semaphore（M2）；SSE 事件清单与 candidateId 归一化（M3）；补三条闸门测试用例（M4）；chat 阻塞 call-timeout 默认下调 150s（M5）；行号引用改块名定位（L1）；408/425 记入后续优化（L2）；OkHttp 显式 followRedirects(false)（L3）；ON_ERROR 不落库措辞修正（L4）；RetryPolicy 保留旧构造器（L5）。
- v1.0（2026-08-25）：初稿。

## 0. 背景与问题清单

模块分层（client / resilience / registry / strategy / adapter）本身健康，本次重构针对代码评审与链路查证确认的 10 个问题：

| # | 问题 | 代码证据（按符号定位，行号会漂移） | 影响 |
|---|------|----------|------|
| P1 | 流式路径 429/5xx 映射与阻塞路径不一致：流式所有非 2xx 一律 `LLM_STREAM_ERROR`，不读响应体、不区分限流/服务端故障 | `GenericChatClient.chatStream` 非 2xx 分支 vs `HttpClientErrorHandler.translate` | 同样的 429，阻塞路径同模型重试 3 次，流式路径不可重试（`RetryPolicy.isRetryable` 仅认 RATE_LIMITED/TRANSIENT）直接跳降级 |
| P2 | `ChatRequest.extraParams` 在 Generic 路径被静默丢弃；Generic 不支持 `response_format`（JSON mode）等 | `GenericChatClient.buildRequestBody`（不读 extraParams）vs `BailianChatClient.resolveCommon`（消费 extraParams + response_format 特判） | 结构化输出只能靠 prompt 约束 + 解析容错；字段为公开 API 却无声失效 |
| P3 | 流式中途降级内容重复：模型 A 已发 N 个 chunk 失败后，模型 B 从头全量输出，无 reset 标记 | `FallbackExecutor#executeStream` javadoc 自认"新模型从头开始生成完整响应"；`StreamFrame.Kind` 仅 CONTENT/REASONING | 用户看到回答突然重复一遍 |
| P4 | **阻塞路径**重试无 jitter、不解析 Retry-After（流式路径 `Retry.backoff` 已带 Reactor 默认 ±50% jitter，两路退避还不同源） | `RetryPolicy.executeWithBackoff` 纯指数退避 vs `retryStream`（Reactor 默认 jitter） | 多请求同步重试（雷群）；429 后退避时长无视服务端指示。项目其他模块均有 jitter（ReconnectBackoff ±20%、LockRetryExecutor U(0.5,1.5)） |
| P5 | 超时全部硬编码、无总时长上限；阻塞路径现状**无界**——read timeout 按次读计算，慢速滴流连接每次成功读即重置，可无限期占用线程 | `GenericChatClient` 构造器超时常量、Bailian/Embedding 同 | 快模型与慢推理模型共用一档；阻塞最坏退避时长无上限（远超此前低估的 6 分钟） |
| P6 | 双 HTTP 栈分裂：阻塞走 RestClient+JDK HttpClient，流式走 OkHttp | `HttpClientFactory` 同时维护两套 | 两套连接池/超时配置；共享 OkHttpClient 逻辑只覆盖流式 |
| P7 | 并发无限流：Generic 流式上限 = boundedElastic 默认（10×CPU）；Bailian 流式上限 = SDK Dispatcher 32/host；阻塞占用 Undertow worker；**排队 >3s 被首包探测斩首**（探测定时器先于排队完成启动） | `GenericChatClient.chatStream`（subscribeOn(boundedElastic)）、SDK `ConnectionConfigurations` 默认 32/32、`ProbeStreamHandler.wrapWithProbe` | 过载表现为探测超时风暴 + 降级链逐个击穿，而非可控排队/拒绝 |
| P8 | 观测盲区：`recordRetryAttempt` 定义未用、无 TTFT 指标、`cacheHitTokens` 解析后无出口 | `LlmMetrics.recordRetryAttempt`、`GenericChatClient.parseTokenUsage` | 重试不可见、流式核心体验指标缺失、缓存命中率不可观测 |
| P9 | 死配置：`app.chat.candidates` 块（probe-timeout-seconds / probe-cache-ttl-seconds / pre-probe-interval-ms + DynamicModelSelector 注释；该类已随 SPI 迁移 commit `06e687d` 删除，配置无代码消费） | application.yml `chat:` 段下 `candidates:` 块（当前约 164-168 行，**实施时按块名 grep 定位**，紧邻其上的 `merge:` executor 配置勿动） | 注释误导运维 |
| P10 | **BYOK per-user 客户端对象图**：apiKey 固化在客户端构造器（`GenericChatClient` 持 `apiKey` 字段），"每用户一把 key"被物化为"每用户一套完整客户端对象图（含熔断/探测/重试装饰器）"，连锁产生 userSnapshots 缓存淘汰、asyncClose 专用线程池、evict 挂接、`u:{userId}:` 命名空间与 `stripUserPrefix` 归一化等全部生命周期债务；**并存在双重解析断链（B4）** | `LlmClientRegistry.userSnapshots/asyncClose/buildUserSnapshot`、`LlmClientFactory.buildSnapshot(List<ResolvedCandidate>)`、`GenericChatClient` 构造器 apiKey 字段 | 复杂度根源：v1.1 评审中 WS4 闸门注册表复用/替换/gauge 生命周期问题（H2）与 BYOK 指标基数问题大半源于此。**详设独立成文：`docs/design/llm-client-stateless.md`（WS0 前置）** |

## 1. 决策记录

> 1. **HTTP 全面 OkHttp 化（用户确认）**——统一指**技术栈统一**：5 个自研客户端阻塞/流式全部走 OkHttp + `HttpClientFactory` 共享池；**不是**单实例两用——见决策 12 的双实例结构。
> 2. **可观测性三项全做，metrics 层（用户确认）**——retry 重试计数、TTFT 首 token 延迟、cacheHit 缓存命中计数。三者所需数据均已在响应中可得（cacheHit：DeepSeek `prompt_cache_hit_tokens`、百炼/OpenAI `prompt_tokens_details.cached_tokens`，`parseTokenUsage` 已解析）。不动 usage 模块与 DB。
> 3. **并发闸门默认启用（用户确认）+ 初值标定与灰度（v1.1 修订）**——application.yml 默认配 chat 64 / embedding 16 / reranking 8（初值选取依据与标定流程见 WS4 §3.4.8）；代码默认 `0=禁用` 作回滚开关。**初值未经压测标定，上线必须走灰度流程**。**闸门仅挂接系统候选（v1.2，决策 14）**。
> 4. **extraParams 走白名单透传，不做任意透传（查证）**——OpenAI 对未知顶层参数 400（"Unrecognized request argument"）；DashScope SDK `parameter()` 是官方逃生舱（透传进原生协议 `parameters` 嵌套对象，服务端宽容）。两路参数模型差异是协议事实，不是遗漏。
> 5. **Bailian SDK Dispatcher 限制用环境变量调优（查证）**——SDK OkHttp 全局单例 Dispatcher 默认 maxRequests/maxRequestsPerHost = 32/32（`ConnectionConfigurations`），可用 `DASHSCOPE_MAXIMUM_ASYNC_REQUESTS(_PER_HOST)` 覆盖；代码层无法 per-candidate 控制。
> 6. **闸门 acquire 先于 probe 订阅**——排队等待不消耗 3s 首包探测预算，消除 P7 的时序耦合。
> 7. **新错误码 `LLM_BUSY(301011)`**——语义：不可同模型重试（`isRetryable=false`）、可跨模型降级（RemoteException → `FallbackEligibility` 自动通过）。
> 8. **reset 帧为增量前端契约**——旧前端忽略未知 SSE 事件 = 行为同现状（内容重复），无回归。
> 9. **闸门生命周期挂接候选注册表（v1.1，H2；v1.2 修订）**——`AdmissionControl` 按 candidateId 全局唯一（`AdmissionControlRegistry`），`LlmClientRegistry.refresh()` 重建客户端时**复用**同一闸门（无新旧双 semaphore 超发窗口）；evict/替换时显式移除 Micrometer gauge，杜绝僵尸序列。**复用判定比较整个 ConcurrencyConfig（v1.2，决策 18）；evict 挂接 `refresh()` 移除路径 + `destroy()`（v1.2，决策 19）；BYOK 淘汰挂接点随 WS0 删除**。
> 10. **公平 Semaphore（v1.1，M2）**——`new Semaphore(n, true)` FIFO：permit 持有为分钟级，非公平模式的 barging 会使早到等待者在持续满载下反复 BUSY（饥饿），公平模式的吞吐损耗在此低争用频次下可忽略。这是对项目内既有 6 处非公平 Semaphore 先例的**有意偏离**，理由如上。
> 11. **OkHttp 显式 `followRedirects(false)`（v1.1，L3）**——JDK HttpClient 默认不跟随重定向，OkHttp 默认跟随；迁移后行为会有差异，显式关闭保持现状语义，MockWebServer 补防漂移断言。
> 12. **双共享实例结构（v1.1，M1，用户确认）**——同一客户端（如 GenericChatClient）按**用途**持有两个共享 OkHttp 引用：阻塞 `(connect, read, call-timeout-ms)` 与流式 `(connect, stream-read-timeout-ms, stream-call-timeout-ms)`；`HttpClientFactory` 按 (connect, read, call) 签名缓存，签名天然不同即天然分实例。实现者不得为省实例把两路合用一个 client——会把阻塞最坏时长放宽到 stream-call 或把流截断在 call-timeout。
> 13. **WS0 前置：客户端无状态化 + 调用时凭据解析（v1.2，用户确认；v2.0 机制更新；详设独立成文 `docs/design/llm-client-stateless.md`）**——pi-ai 模式（§11）在本方案 WS1–WS8 **之前**落地：凭据与连接分离，端点以 `ResolvedEndpoint`（baseUrl/apiKey/endpoints）在**链装配期**解析并闭包进能力句柄（v2.0 取代 v1.0 的"ChatRequest 请求级 binding 字段"方案，凭据不进请求对象）；系统候选与 BYOK 共用无状态协议适配器；消除 per-user 客户端对象图及全部连锁债务。**BYOK 弹性降格为 retry-only + 降级链**（无熔断、无闸门、无 per-user 指标；retry 落点 = ByokChatCall 内共享 RetryPolicy，v2.0 决策 7）
> 14. **BYOK 流量不进 per-candidate 闸门/指标（v1.2，用户确认）**——闸门保护的是本地共享资源（线程/连接池），系统 key 按 candidate 限流；BYOK 上游配额 per-key，由 Retry-After/降级表达，本地不做 per-user 限流。经 WS0 后 BYOK 调用不经 Resilient 装饰器路径，**天然分离，无需前缀判断**（实现细节见 `llm-client-stateless.md` WS-C）。
> 15. **Retry-After 尊重服务端（v1.2，用户确认）**——`retryAfterMs ≤ 60s` 原样等待（不叠 jitter、**不受 maxDelayMs 约束**）；`> 60s` 视为服务端明示长时间限流，**不可同模型重试、直接降级**（放弃阈值，pi `provider-retry` 同型：超限即抛）。maxDelayMs 仅约束普通指数退避，两者解耦——maxDelayMs 是全局旋钮，调大会同步拖长普通网络错误的退避，故不做"调大 maxDelayMs 来容纳 Retry-After"。
> 16. **流式闸门 acquire 非阻塞轮询（v1.2）**——订阅线程先 `tryAcquire(0)`，未得则 `Mono.delay` 短周期轮询，总预算按**流逝时间**判定（订阅起算）；全程不占任何调度器阻塞线程。消除 v1.1 设计的 acquireMono-on-boundedElastic 缺陷：阻塞 tryAcquire 的等待任务排在长流任务之后时，排队时间不计入 acquire-timeout、也不触发探测，过载时退化为不可见无限排队（与 P7 目标相悖）。
> 17. **release 双标志（v1.2）**——`acquired` 仅在成功获取时置位；`release()` 仅当 acquired 为真时执行（CAS 防双释放）。acquire 失败路径（BUSY/超时/中断）**绝不释放**——防"未获取而释放"导致闸门超发。
> 18. **注册表复用判定比较整个 ConcurrencyConfig（v1.2）**——maxConcurrent 或 acquireTimeoutMs 任一变化即替换实例；仅比较 maxConcurrent 会导致仅改超时经 refresh() 后静默不生效。
> 19. **`refresh()` 移除候选路径挂接 evict（v1.2，用户确认）**——refresh() 关闭消失候选的旧客户端处旁路 `evictAdmissionControlQuietly` + `evictCircuitBreakerQuietly`（**顺带修复熔断器注册表的既有缺口**）；destroy() 保留既有挂接；BYOK 淘汰挂接点随 WS0 删除。AC10 的"无僵尸序列"承诺自此完整成立。

## 2. 现状关键事实（实施前提）

- **流式线程模型**：Generic 流式 = OkHttp 同步 `call.execute()` 独占一个 boundedElastic 线程；Bailian 流式 = SDK `EventSource` 异步 enqueue，不占业务线程。boundedElastic 全项目仅 GenericChatClient 使用（默认 10×CPU 线程 + 100K 任务队列）。
- **探测**：`ProbeStreamHandler` 3s（`app.llm.resilience.probe.probe-timeout-ms`）首包超时 → 熔断计数 + `ProbeTimeoutException`（可重试）；`ProbeHandler` 叠加 `SharedProbeRegistry` 并发探测去重。
- **熔断**：`ModelCircuitBreakerRegistry` 无 slow-call 统计，仅失败计数（failure-threshold 5 / open 30s / half-open 2）。`LlmCircuitBreakerAdapterRegistry.getOrCreate` 按 candidateId 复用适配器——闸门注册表（决策 9）镜像的结构；**其 evict 仅挂接 BYOK 淘汰与 destroy，系统级 refresh() 移除候选不清理（既有缺口，WS4 决策 19 一并修复）**。
- **注册表刷新路径**：`LlmClientRegistry.refresh()` 全量重建 wrapped clients（关闭快照中消失的旧实例——闸门/熔断 evict 挂接点，见决策 19）。**BYOK 现状（WS0 待消除）**：用户快照 Caffeine（maxSize 1000 + TTL 1h）→ `asyncClose`（专用小池）+ `evictCircuitBreakerQuietly`；candidateId 命名空间 `u:{userId}:{modelCode}`；BYOK 仅 CHAT 能力、仅 GenericOpenAiProvider 协议（`LlmClientFactory.buildSnapshot(List<ResolvedCandidate>)`）。
- **持久化事实（L4 修正）**：`StreamCompletionHelper.onComplete` 仅 ON_COMPLETE 落库；ON_ERROR/CANCEL **不落库**（"取消即作废"，含意外断连），usage 由策略层独立记录。
- **candidateId 暴露事实（M3；WS0 后消解）**：`FallbackMeta.requestedModel` 即 candidateId，现状经 `event: fallback` 尾帧已暴露给前端（含 BYOK `u:{userId}:{modelCode}` 形态）；WS0 落地后 BYOK 对外 candidateId = modelCode（无前缀），归一化问题连根消解，`stripUserPrefix` 随之删除。
- **Semaphore 先例（M2）**：项目内 6 处 `new Semaphore` 全部非公平（ScopeWriteGate / SandboxService / EvaluationExecutorConfig×2 / DefaultTaskScope 等）。
- **配置约定（v1.2 措辞修正）**：candidate `params` 为 `Map<String,Object>`，Spring 绑定**键名原样保留**（camelCase 不会被丢弃——`ChatRequest.fromDefaults` 即用 camelCase 键 `maxTokens`/`temperature` 直接 get）。**新键统一 kebab-case 是约定**（对齐 `batch-size`、`sdk-client`、`thinking` 嵌套 map 先例），与既有 camelCase 键在同一个 map 内并存，实施时按各键文档为准。per-capability 覆盖先例：`ResilienceConfig.retryOverrides`（键 = 能力名小写）。
- **构造链路**：`LlmClientFactory.buildChain → wrapWithResilience` 处持有 candidate / capability / ResilienceConfig，是闸门与超时配置的统一注入点（WS0 后仅系统链路）。

## 3. 工作流详设

### WS0 客户端无状态化与调用时凭据解析（P10，前置——独立设计文档）

**详设见 `docs/design/llm-client-stateless.md`**（含问题清单 B1–B6、决策记录、五个工作流 WS-A/B/C/D/E、B4 潜伏断链的发现与钉死测试、验收标准、pi-ai 调研摘要）。本文对 WS0 的依赖为接口级结论：

- BYOK 调用不经 Resilient 装饰器路径（共享无状态协议适配器 + `ResolvedEndpoint` 装配期闭包，v2.0）→ **WS4 闸门/指标仅系统候选，天然路径分离，无需前缀判断**（决策 14）；
- BYOK 对外 candidateId = modelCode（无 `u:` 前缀）→ WS5 reset from/to 无需归一化、WS7 指标 candidateId tag 基数收敛到模型数级别；
- `stripUserPrefix`/`userSnapshots`/`asyncClose` 等对象图债务随 WS0 删除 → §3.4.4 生命周期挂接只剩系统级 refresh/destroy 路径；
- pom test 依赖 `mockwebserver:4.12.0` 随 WS0 的 WS-A 引入，本方案 WS1/WS3 直接使用。

### WS1 流式错误映射对齐 + Retry-After + jitter（P1、P4）

**改动文件**：`exception/RateLimitedException.java`（新）、`HttpClientErrorHandler`、`GenericChatClient`、`RetryPolicy`

1. 新增 `RateLimitedException extends RemoteException`（错误码 `LLM_RATE_LIMITED`），携带 `@Nullable Long retryAfterMs`。
2. `HttpClientErrorHandler` 抽取公共静态方法：

```java
static RuntimeException translateStatus(String operation, String url,
        int status, String body, @Nullable String retryAfterHeader)
// 429          → RateLimitedException（retryAfterMs = parseRetryAfter(retryAfterHeader)）
// status >= 500 → RemoteException(LLM_TRANSIENT_ERROR)   ← ERROR 日志
// 其余 4xx      → RemoteException(LLM_STREAM_ERROR)
```

   现有 `translate()` 的 `RestClientResponseException` 分支委托之（`retryAfterHeader = rcre.getResponseHeaders().getFirst("Retry-After")`）。`parseRetryAfter` 支持**两种形态**（决策 15）：秒数整数；HTTP-date（`Date.parse(header) - now`，解析失败或为过去 → null）。**>60_000ms 不截断**，保留原值交由 `isRetryable` 判定放弃（放弃阈值语义，取代 v1.1 的 cap-截断）。
3. `GenericChatClient.chatStream` 非 2xx 分支：`response.peekBody(4096).string()` 读错误体 + `response.header("Retry-After")` → `translateStatus("Chat Stream", url, code, body, retryAfter)`。阻塞与流式行为对齐。
4. `RetryPolicy` 统一退避计算：

```java
long computeDelay(Throwable e, int attempt) {
    if (e instanceof RateLimitedException rle && rle.retryAfterMs() != null) {
        return rle.retryAfterMs();   // 服务端指示原样生效：≤60s 不叠 jitter、不受 maxDelayMs 约束（决策 15）
    }
    long base = Math.min(baseDelayMs * (long) Math.pow(multiplier, attempt), maxDelayMs);
    return (long) (base * ThreadLocalRandom.current().nextDouble(0.5, 1.5));  // 对齐 LockRetryExecutor 先例
}
```

   - **放弃阈值**：`isRetryable` 对 `RateLimitedException && retryAfterMs > 60_000` 返回 **false**（服务端明示长时间限流，同模型等待无意义，立即跨模型降级；pi `provider-retry#validateServerRetryDelayMs` 同型——超限即抛，不截断不等待）。
   - 阻塞路径 `executeWithBackoff` 改用 `computeDelay`。
   - 流式路径 `retryStream` 将 `Retry.backoff(...)` 替换为 `Retry.from(...)` 自定义 companion（统计 attempt、`filter(isRetryable && !emitted)`、`Mono.delay(computeDelay)`），保留"已发数据不重试"与耗尽透传语义。阻塞/流式退避行为自此同源（阻塞补 jitter，流式由 Reactor 默认 jitter 换为显式同源实现）。
5. **兼容性（L5）**：`RetryPolicy` 保留旧构造器 `RetryPolicy(RetryConfig)` 委托新构造器（candidateId=null、metrics=null），`RetryPolicyTest` 现有 `new RetryPolicy(new RetryConfig(...))` 用法不破坏。

**测试**：`HttpClientErrorHandlerTest` 补 429/5xx/4xx/Retry-After（秒数 + HTTP-date）分支；`RetryPolicyTest` 补 jitter 上下界、Retry-After 原样延迟（不受 maxDelay 约束）、Retry-After>60s 不可重试直接降级；新增 MockWebServer 流式 429/5xx 集成测试（见 §6）。

### WS2 超时配置化 + 总时长上限（P5）

**改动文件**：`llm/client/TimeoutParams.java`（新）、5 个客户端构造器、`HttpClientFactory`、application.yml 注释

1. 新增 `TimeoutParams` record，从 candidate `params` 读取（解析风格对齐 `BailianEmbeddingClient.resolveBatchSize`：Number/可解析 String，非法回落默认 + WARN）：

| 键（kebab-case） | 语义 | 默认值 |
|---|---|---|
| `connect-timeout-ms` | 连接超时 | 10000 |
| `read-timeout-ms` | 阻塞读超时 | chat 120000 / embedding 30000 / rerank 对齐现状常量（实施时核对） |
| `call-timeout-ms` | 阻塞调用总时长上限（OkHttp callTimeout） | chat **150000**（M5 修订，见下）/ 其余 180000 |
| `stream-read-timeout-ms` | 流式相邻 chunk 间隔上限 | 120000 |
| `stream-call-timeout-ms` | 单流总时长上限 | 300000 |

2. **chat 阻塞 call-timeout 取 150s 的依据（M5）**：150s > read-timeout 120s（保证纯读停滞仍由 read timeout 先触发、走既有可重试路径），且最坏单模型阻塞降级时长 = 3×150s + 退避(0.5+1，cap 5s) ≈ **452s ≈ 7.5 分钟**，低于 180s 默认的 9.2 分钟；更重要的是现状（无 callTimeout）为**无界**，任何有限值都是改善。需要更快让位的候选可 per-candidate 下调 `call-timeout-ms`。**全链口径（v1.2 补）**：跨模型全链最坏 ≈ N × 单模型上界（N = 链长 2–5；熔断打开与降级会提前截断大部分场景），容量规划按此标注。
3. **双实例结构（决策 12）**：`GenericChatClient` 持有 `blockingClient = shared(connect, read, call)` 与 `streamingClient = shared(connect, streamRead, streamCall)` 两个引用；Embedding/Rerank 仅持阻塞实例。Bailian SDK 路径（BailianChatClient/BailianEmbeddingClient）经 `ConnectionOptions` 仅 connect/read 生效——SDK 未透传 callTimeout，为已知限制（注释注明）。
4. `HttpClientFactory.sharedOkHttpClient` 缓存键从 `connect_read` 扩展为 `connect_read_call`。
5. application.yml 候选 `params` 增加注释示例（默认值不变，零行为变化）。**WS0 的共享 Generic 协议适配器按能力默认值取超时（BYOK 选择无 params 覆盖，属可接受简化）。**

**测试**：`TimeoutParams` 解析单测（合法/非法/缺失/类型容错）。

### WS3 HTTP 栈统一 OkHttp（P6）

**改动文件**：`HttpClientFactory`、`GenericChatClient`、`GenericEmbeddingClient`、`GenericRerankClient`、`BailianRerankClient`

1. `HttpClientFactory`：删除 `buildRestClient` / `HttpHandles` / JdkClientHttpRequestFactory 相关代码；`sharedOkHttpClient(connect, read, call)` 成为唯一传输出口，**构建时显式 `.followRedirects(false)`（决策 11）**；`closeAll()` 生命周期不变。
2. 4 个 RestClient 使用方（Generic×3 + BailianRerank）替换为共享 OkHttp 同步调用，统一模板：

```
POST url（Bearer + JSON body）
├─ 非 2xx → translateStatus(op, url, code, peekBody(4096), header("Retry-After"))
├─ IOException → translate(op, url, e)（既有 LLM_TRANSIENT_ERROR 路径）
└─ 2xx → body string → 既有 Jackson 解析（零改动）
```

3. `HttpClientErrorHandler` 的 RestClientException 分支保留（防模块外复用，零风险）。
4. **对外契约不变**：异常类型 / 错误码 / 超时语义 / 重定向行为（不跟随）保持，undertow/web 层零改动。BailianChatClient / BailianEmbeddingClient 走 SDK，不涉及。
5. **已知行为差异记录（v1.2 补）**：明文 `http://` 端点下 JDK HttpClient 会尝试 h2c 升级、OkHttp 恒 HTTP/1.1——本项目 LLM 端点均为 https，无影响；记录于此防实施后误报。
6. test 依赖 `mockwebserver:4.12.0` 已随 WS0 引入（见 `llm-client-stateless.md` WS-A 改动文件），本工作流直接使用。

**测试**：4 个客户端阻塞路径 MockWebServer 移植/新增（成功解析、429/5xx/429+Retry-After 映射、超时 wiring 断言、**301 重定向不跟随断言**）。

### WS4 并发准入闸门（P7；v1.2 修订：仅系统候选 + 非阻塞轮询 + release 双标志）

**改动文件**：`RemoteErrorCode`、`RetryPolicy`（注释级）、`config/ConcurrencyConfig.java`（新）、`ResilienceConfig`、`resilience/AdmissionControl.java`（新）、`resilience/AdmissionControlRegistry.java`（新）、`AbstractResilientClient`、`ResilientChatClient`、三个 Resilient 装饰器构造链（`CapabilityStrategy.wrapWithResilience` 签名）、`LlmClientFactory`、`LlmClientRegistry`（evict 挂接）、`LlmMetrics`、application.yml

#### 3.4.1 错误语义

`RemoteErrorCode.LLM_BUSY(301011, "模型并发已达上限")`。`RetryPolicy.isRetryable`：LLM_BUSY 不在可重试码内（显式注释）；`FallbackEligibility`：RemoteException 自动可降级 → **立即换下一模型**。

#### 3.4.2 配置三级解析（优先级：candidate params > capability override > global）

```yaml
app.llm.resilience:
  concurrency:                      # 全局默认（chat 生效值）——仅作用于系统候选（决策 14）
    max-concurrent: 64
    acquire-timeout-ms: 1000
  concurrency-overrides:            # 镜像 retryOverrides 模式（键 = 能力名小写）
    embedding: {max-concurrent: 16, acquire-timeout-ms: 5000}
    reranking: {max-concurrent: 8,  acquire-timeout-ms: 5000}
  # per-candidate 终态覆盖（仅数量）：candidate params 加 max-concurrent: 32
```

`ConcurrencyConfig(Integer maxConcurrent, Long acquireTimeoutMs)`，代码默认 `maxConcurrent=0`（禁用，回滚开关）+ `acquireTimeoutMs=1000`，`effective*` 方法镜像 `RetryConfig`。

#### 3.4.3 AdmissionControl（per-candidate，经注册表获取；仅系统候选注册）

```java
class AdmissionControl {
    private final String candidateId;
    private final Semaphore permits;                 // new Semaphore(maxConcurrent, true) —— 决策 10 公平模式
    private final long acquireTimeoutMs;
    private final AtomicBoolean acquired = new AtomicBoolean(false);   // 仅成功获取时置位
    private final AtomicBoolean released = new AtomicBoolean(false);   // release 仅当 acquired 为真时执行（CAS 防双释放）
    // 阻塞（调用方为业务线程，无调度器盲区问题）：tryAcquire(ms) → false 抛 RemoteException(LLM_BUSY)；
    //       InterruptedException → Thread.currentThread().interrupt() + 抛 RemoteException(LLM_BUSY, "interrupted")。
    //       两条失败路径均未获许可，acquired=false → 不释放（决策 17，防"未获取而释放"超发）
    // 流式（决策 16 非阻塞轮询，全程不占任何阻塞线程）：
    //   acquireMono() = 订阅线程 tryAcquire(0) 成功 → Mono.empty；
    //   否则 Flux.interval(pollMs=50).handle(tick → tryAcquire(0) 成功即完成)，
    //   以订阅起算的流逝时间 ≥ acquireTimeoutMs 仍未得 → Mono.error(LLM_BUSY)。
    //   排队/等待时间全部计入 acquire-timeout 预算（消除 boundedElastic 任务队列盲区）
    // metrics：llm.busy.rejected{candidateId} 计数 + llm.inflight{candidateId} gauge（maxConcurrent - availablePermits）
}
```

#### 3.4.4 AdmissionControlRegistry（v1.1 H2 建立，v1.2 修订判定与挂接）

```java
@Component
class AdmissionControlRegistry {
    private final ConcurrentHashMap<String, AdmissionControl> controls;
    // getOrCreate(candidateId, ConcurrencyConfig)：
    //   不存在 → 创建（注册 gauge，幂等保护）
    //   存在且 config 全等（maxConcurrent + acquireTimeoutMs——决策 18）→ 复用
    //       （refresh() 重建客户端后仍指向同一闸门 → 无新旧双 semaphore 超发窗口）
    //   存在但 config 任一变化 → 替换新实例（旧 gauge 移除、新 gauge 注册）
    // evict(candidateId)：移除注册表条目 + LlmMetrics.removeInflightGauge(candidateId)
    //       （MeterRegistry.remove + 去重集合同步移除，杜绝强引用 gauge 泄漏与僵尸序列）
}
```

**生命周期挂接（决策 19）**：`LlmClientRegistry.refresh()` 关闭消失候选旧客户端处 + `destroy()` 旁路调用 `evictAdmissionControlQuietly` 与 `evictCircuitBreakerQuietly`（后者顺带修复熔断器注册表既有缺口）。v1.1 设计中的 BYOK 淘汰挂接点随 WS0 删除（BYOK 无对象图）。

**接受的残余窗口（文档化）**：仅 config 变更替换瞬间——旧在飞请求持旧闸门引用继续放行至 drain 完成（分钟级）。系统级 refresh() 因注册表复用**不存在**超发窗口；v1.1 的 BYOK 两个残余窗口随 WS0 消失。

#### 3.4.5 阻塞接入（`AbstractResilientClient.executeResilient`）

`acquire → circuitBreaker.execute(retryPolicy.executeWithBackoff(action)) → finally release（仅 acquired 时生效）`——**permit 跨同模型重试持有**（退避 sleep 期间不放），重试风暴不放大并发。acquire 失败打 `busy.rejected`，**不打 error latency**（acquire 在 metrics 计时起点之前）。

#### 3.4.6 流式接入（`ResilientChatClient.chatStream`）

`admissionControl.acquireMono().thenMany(executeStream(...)).doFinally(release)`——**acquire 在 executeStream 订阅之前完成，probe 定时器在 acquire 后才启动**（决策 6：排队不消耗探测预算）。CANCEL 信号经 doFinally 释放 permit（acquired 为真时）；acquireMono 自身失败（BUSY/超时）时 doFinally 触发但 acquired=false → 不释放（决策 17）。无泄漏、无超发。

#### 3.4.7 注入点

`LlmClientFactory.wrapWithResilience`（**仅系统链路**——WS0 后 BYOK 不经装饰器，天然不进闸门，决策 14，无需前缀判断）：`resolveConcurrencyConfig(cap)` + candidate `params.max-concurrent` 终态覆盖 → 经 `AdmissionControlRegistry.getOrCreate(candidateId, config)` 获取实例注入装饰器（不再每次 new）。

#### 3.4.8 初值依据与上线流程（v1.1 H1；v1.2 修订口径）

**初值选取**：
- chat 64（**系统候选**粒度）：现状 Generic 流式有效上限 = boundedElastic（10×CPU，典型 80–160），正常流量几乎全落在主候选——64/candidate 对流式**基本不收紧**（boundedElastic 仍是约束），对阻塞路径则是**首次引入上限**（现状无界、直接侵蚀 Undertow worker）——收紧即目的，但幅度温和；
- embedding 16：上游 ETL 线程池（etl-io 8-16 + merge 4-8）已限并发，permit 粒度 = embedBatch 调用（ScopedTasks 并发在 permit 内部），16 与上游容量匹配；批量回填期间可 per-candidate 上调；
- reranking 8：检索路径同步调用，保守取值。

**标定补充约束（v1.2）**：非阻塞轮询（决策 16）已消除 acquire 排队盲区，但流执行本体仍占 boundedElastic 线程——**各系统 chat 候选 permit 之和仍应 ≤ boundedElastic 线程数**作为标定输入（超过时新增流在调度器排队，不占探测/acquire 预算但直接拖长 TTFT）。

**上线流程（必须执行）**：
1. 上线前按目标峰值并发压测标定（流式 sizing 用 Little's law：并发流数 ≈ 流式 QPS × 平均流时长；permit 为分钟级持有）；
2. 灰度：先对 1 个非主候选启用（per-candidate `max-concurrent`），盯 `llm.busy.rejected`（应≈0）与 `llm.inflight` 分布；
3. 全量启用 yml 值；`llm.busy.rejected` 持续 > 0 即回滚（删 concurrency 块，代码默认 0=禁用）。

#### 3.4.9 Bailian SDK Dispatcher（决策 5）

运维文档注明 `DASHSCOPE_MAXIMUM_ASYNC_REQUESTS(_PER_HOST)` 环境变量（建议 ≥ 同 host 各系统 candidate 闸门之和；BYOK 经 Generic 协议不占 SDK Dispatcher）。

**测试**：`AdmissionControlTest`（耗尽→LLM_BUSY、acquire 超时、取消释放、幂等 release、**acquire 失败路径 permit 计数不变**（决策 17）、**中断恢复：interrupt 后标志位还原且无 permit 泄漏**、**非阻塞轮询：acquire 等待期间不占阻塞线程且总预算按流逝时间判定**）；`ResilientChatClient` 流式 acquire/release-on-cancel；`LlmClientFactory` 三级配置解析；`AdmissionControlRegistryTest`（**refresh 复用同实例**、**config 任一字段变更即替换** + gauge 移除、**refresh() 移除候选路径 evict 清理**）；**permit 跨同模型重试持有**（阻塞退避 sleep 期间 inflight 恒 1）。

### WS5 流式降级 reset 标记（P3）

**改动文件**：`mode/StreamFrame`、`chat/service/SseStreamBridge`、`chat/service/impl/ChatServiceImpl`（~~`LlmClientRegistry.stripUserPrefix` public 化~~ v1.2 取消——WS0 后 candidateId 已无前缀）

1. `StreamFrame.Kind` 增加 `RESET`，工厂 `reset(String payload)`；payload JSON：`{"from": "<candidateId>", "to": "<candidateId>"}`——WS0 后 BYOK candidateId = modelCode（无 `u:` 前缀），from/to **无需归一化处理**，直接使用。
2. `SseStreamBridge.sendFrame`：RESET → `event: reset`（独立命名事件，不进 content 通道）。
3. **SSE 事件总清单（v1.1 M3；v1.2 修订 fallback 行）**：

| 事件 | 时机 | 语义 |
|---|---|---|
| `data:`（无名） | 流中 | content 增量 |
| `event: reasoning` | 流中 | 思考链增量 |
| `event: reset` | **流中（新增）** | 模型切换：**清空已累积回答缓冲（含 reasoning 缓冲）** + "已切换模型重试"提示，随后内容来自新模型 |
| `event: usage` / `references` / `agentMetadata` | 尾帧 | 终值 |
| `event: fallback` | 尾帧 | 最终服务模型 ≠ 请求模型（含 requestedModel；**WS0 后恒为 modelCode 归一形态，M3 延伸项消解**） |
| `event: error` / `canceled` | 终止 | 失败/取消 |

4. `ChatServiceImpl.chatStream` 的 per-candidate lambda：
   - 共享 `AtomicBoolean emittedAny`，每个 attempt 的 frames 包 `.doOnNext(__ -> emittedAny.set(true))`——**任何帧（含 reasoning）都置位（v1.2 注明）**：前端 reasoning 缓冲与 content 缓冲同需 reset 清空，仅 content 置位会导致切换后 reasoning 重复；
   - lambda 入口判断 `switched = !attempted.isEmpty()`（add 之前）且 `emittedAny.get()` 为真 → `Flux.concat(Flux.just(reset(from, to)), sr.frames())`。
   - **零帧失败（如探测超时）不发 reset**，避免噪音。
   - `ChatModelAdapter` / `FallbackExecutor` 零改动（reset 在 service 层注入，Spring AI 链路无感知）。
5. 前端契约：收到 `event: reset` → 清空已累积的回答缓冲区 + 提示。旧前端忽略未知事件 = 现状行为，无回归。
6. **持久化（v1.1 措辞修正 L4）**：`StreamCompletionHelper` 现状 ON_ERROR/CANCEL **不落库**（"取消即作废"）——降级时模型 A 的部分内容本就不持久化，最终仅成功模型 B 全文落库，**与 reset 清屏后的前端视图天然自洽**。实施时勿画蛇添足去保留 A 片段。

**测试**：`SseStreamBridgeTest` RESET 分支；`ChatServiceImpl` 降级重入且已发帧 → 恰一个 reset 帧在 B 内容之前（payload from/to = modelCode）；仅 reasoning 帧已发 → 同样触发 reset；零帧失败 → 无 reset。

### WS6 extraParams 白名单透传（P2 修正版）

**改动文件**：`GenericChatClient`

```java
private static final Set<String> EXTRA_PARAM_ALLOWLIST =
    Set.of("response_format", "stop", "seed", "frequency_penalty", "presence_penalty");
private static final Set<String> WARNED_UNKNOWN_KEYS = ConcurrentHashMap.newKeySet();
```

- `buildRequestBody`：白名单键合入 body（打通 JSON mode：`response_format: {"type": "json_object"}`，Map 形态原样透传）；非白名单键**不透传**（决策 4：OpenAI 严格校验未知参数会 400）且 **每键首见 WARN**（`WARNED_UNKNOWN_KEYS.add` 成功时打日志，防调用方常态携带未知键导致逐请求刷屏——v1.2 修订）。
- Bailian 路径不变（`parameter()` 官方逃生舱语义）。

**测试**：白名单合入 body、非白名单丢弃 + 首见告警（第二次同键不再打）、空 map 无日志。

### WS7 观测补齐（P8，metrics 层；v1.2 补流式接线）

**改动文件**：`RetryPolicy`、`ResilientChatClient`、`LlmMetrics`、`LlmClientFactory`

| 指标 | 类型 | 打点位置 | 数据来源 |
|---|---|---|---|
| `llm.retry.attempts{candidateId, result}` | counter | `RetryPolicy` 每次重试决策（result: `retry` / `exhausted`） | 接线已定义未用的 `recordRetryAttempt` |
| `llm.chat.ttft{candidateId}` | timer | `ResilientChatClient.chatStream` `doOnSubscribe` 捕获起点 + `doOnNext` 首包（AtomicBoolean 一次性；**起点=订阅时而非组装时**，v1.2 修订） | 订阅起始 nanos |
| `llm.chat.tokens{candidateId, operation=cache_hit}` | counter | **阻塞**：`recordTokensIfPresent`（`cacheHitTokens != null && > 0`）；**流式（v1.2 补）**：`chatStream` 对轮末汇总包（`StreamChunk.tokenUsage() != null`）doOnNext 打点——覆盖流式主路径，此前仅阻塞接线对以流式为主的聊天产品基本恒空 | `TokenUsage.cacheHitTokens`（`parseTokenUsage` 已解析 DeepSeek/百炼两形态；Bailian SDK 路径实施时核对 `GenerationUsage` cached 字段） |
| `llm.busy.rejected{candidateId}` / `llm.inflight{candidateId}` | counter/gauge | `AdmissionControl`（随 WS4，**仅系统候选注册——决策 14**） | 闸门内部状态 |

- **指标基数（v1.2 说明）**：candidateId tag 随 WS0 归一为 modelCode（系统候选 id 本即 modelCode 形态；BYOK 不再产生 `u:{userId}:` per-user 序列），基数收敛到模型数级别，v1.1 评审的 BYOK 基数问题连根消解。
- `RetryPolicy` 新增构造器 `(String candidateId, @Nullable LlmMetrics metrics)`，旧构造器保留委托（见 WS1 第 5 点），由 `LlmClientFactory` 构造时注入新参。

**测试**：mock `LlmMetrics` 断言三类打点触发条件（含流式轮末包 cache_hit）。

### WS8 清理（P9）

删除 application.yml `app.chat.candidates` 死配置块（`probe-timeout-seconds` / `probe-cache-ttl-seconds` / `pre-probe-interval-ms` + DynamicModelSelector 注释；**按块名 grep `app.chat` 下 `candidates:` 定位**——勿按行号，紧邻其上的 `merge:` executor 配置保留）。

## 4. 配置总览（新增项）

```yaml
app.llm.resilience:
  retry:            # 既有，不变（maxDelayMs 仅约束普通指数退避——与 Retry-After 解耦，决策 15）
  circuit-breaker:  # 既有，不变
  probe:            # 既有，不变
  concurrency:                      # ★ 新增（WS4，仅系统候选）——初值待压测标定，上线走 §3.4.8 灰度流程
    max-concurrent: 64
    acquire-timeout-ms: 1000
  concurrency-overrides:            # ★ 新增
    embedding: {max-concurrent: 16, acquire-timeout-ms: 5000}
    reranking: {max-concurrent: 8,  acquire-timeout-ms: 5000}

app.llm.capabilities.chat.candidates:
  - id: qwen3.8-max
    # ...
    params:
      # ★ 新增超时键（WS2，全部可选，默认值见 §3 WS2 表）
      # connect-timeout-ms: 10000
      # read-timeout-ms: 120000
      # call-timeout-ms: 150000
      # stream-read-timeout-ms: 120000
      # stream-call-timeout-ms: 300000
      # ★ 并发终态覆盖（WS4）
      # max-concurrent: 32
```

## 5. 错误语义表（新增/变更）

| 错误码 | 触发 | 同模型重试 | 跨模型降级 | 备注 |
|---|---|---|---|---|
| `LLM_RATE_LIMITED`（429） | 阻塞 + **流式（新增对齐）** | ✅（Retry-After ≤60s **原样等待**、不受 maxDelay 约束；**>60s 不重试直接降级**——决策 15） | ✅ | `RateLimitedException` 携带 retryAfterMs（秒数/HTTP-date） |
| `LLM_TRANSIENT_ERROR`（5xx/IO） | 阻塞 + **流式（新增对齐）** | ✅（jitter 指数退避） | ✅ | |
| `LLM_STREAM_ERROR`（其余 4xx） | 阻塞 + 流式 | ❌ | ✅ | 流式现携带错误体详情；408/425 瞬态化见 §10 |
| `LLM_BUSY`（301011，新增） | 闸门 acquire 超时/中断（**仅系统候选**） | ❌ | ✅ | 立即换下一模型 |

## 6. 测试策略

- **新增 test 依赖**：`com.squareup.okhttp3:mockwebserver:4.12.0`（与 okhttp 4.12.0 对齐，项目当前无 HTTP 层测试基建；**随 WS0 引入**，后续工作流直接使用）。
- **WS0**：测试策略见独立文档 `llm-client-stateless.md` §5（B4 断链钉死测试、MockWebServer 绑定覆盖、全量回归清单）；mockwebserver 依赖随其 WS-A 引入。
- **MockWebServer 覆盖**（WS1/WS3）：4 个自研客户端阻塞路径（成功解析、429/5xx/4xx 映射、Retry-After 头含 HTTP-date）；GenericChatClient 流式 429/5xx；**301 重定向不跟随**（L3 防漂移断言）。
- **纯单测**：`RetryPolicy`（jitter 界 [0.5×, 1.5×)、Retry-After 原样延迟与 >60s 放弃、emitted 语义回归）；`AdmissionControl`（含中断恢复、**acquire 失败路径 permit 计数不变**、非阻塞轮询预算判定）；`AdmissionControlRegistry`（refresh 复用、**整 config 比较替换**、refresh/destroy evict 的 gauge 生命周期）；`TimeoutParams`；`ConcurrencyConfig` 三级解析；`buildRequestBody` 白名单（首见告警）；`SseStreamBridge` RESET；`ChatServiceImpl` reset 注入条件。
- **回归**：`GenericChatClientSseTest`（readSse 静态逻辑不动）、`FallbackExecutorTest`、`CircuitBreakerTest`、`RetryPolicyTest`（旧构造器路径不破坏）全绿。

## 7. 执行顺序与依赖

```
WS0（客户端无状态化——详设见 `docs/design/llm-client-stateless.md`，独立提交）
 → WS1（错误映射/Retry-After/jitter）
   → WS2（TimeoutParams + 双实例结构）
     → WS3（OkHttp 统一，依赖 WS2 参数结构与 WS1 translateStatus）
       → WS4（闸门，复用 WS1 错误码体系；仅系统候选）
         → WS5 / WS6 / WS7（相互独立，可并行）
           → WS8（清理收尾）
```

每批独立提交；遵循 AGENTS.md 协议：**每个被改符号编辑前 `impact(target, upstream)`（HIGH/CRITICAL 先报告），提交前 `detect_changes()` 核对影响面**。

## 8. 风险与回滚

| 风险 | 缓解 | 回滚 |
|---|---|---|
| **WS0 行为变更：BYOK 弹性降格为 retry-only + 降级（无熔断/探测/闸门）** | 决策 13 论证：per-(user,model) 熔断统计稀释几乎不打开、共享聚合会跨用户污染；BYOK 失败面为 4xx（直接降级）+ 瞬态（重试覆盖）。灰度期盯 BYOK 用户错误率与降级事件 | revert WS0 提交（独立提交，WS1-WS8 不依赖其内部结构） |
| **WS0 影响面大（协议层/Registry/Factory/ServiceImpl/agent 装配链，v2.0）** | 全库 `u:` grep 清零 + 系统路径零变化断言 + ChatServiceImpl/agent/ChatModelAssembler 回归；AGENTS.md impact 协议逐符号核查；String 装配重载删除使漏网调用方编译期暴露 | 同上 |
| WS4 初值未经标定，闸门误伤正常流量（H1） | §3.4.8 强制流程：压测定值 → 单候选灰度 → 盯 `llm.busy.rejected`（应≈0）→ 全量；per-candidate `max-concurrent` 可放大；标定含"permit 之和 ≤ boundedElastic 线程数"约束 | yml 删 concurrency 块即禁用（代码默认 0） |
| ~~WS4 acquire 排队盲区~~（v1.2 已消除） | 决策 16 非阻塞轮询：等待全程计入 acquire-timeout 预算、不占阻塞线程；测试覆盖预算判定 | — |
| gauge 泄漏 / 僵尸序列 / 新旧闸门超发（H2） | `AdmissionControlRegistry` 整 config 比较复用/替换（决策 18）；refresh 移除路径 + destroy 显式 `MeterRegistry.remove`（决策 19）；残余窗口仅 config 替换瞬间（§3.4.4） | registry evict 路径与熔断器 evict 同构，已验证 |
| WS3 传输替换引入行为偏差 | MockWebServer 全覆盖 4 客户端；错误码/超时/重定向语义断言保不变；h2c 差异已记录（§3 WS3 第 5 点） | revert WS3 提交（WS1/WS2 独立成立） |
| WS2 双实例误实现为单实例（M1） | AC3 显式断言"流式 call 生效值 = stream-call-timeout-ms"；代码评审对照决策 12 | — |
| WS2 stream-call-timeout 误杀长生成 | 默认 300s 宽裕；per-candidate 可调大；0 = 禁用（OkHttp callTimeout(0) 即不限） | 参数删除回落默认 |
| WS5 前端未同步 | 增量契约，忽略未知事件无回归 | revert 后行为同现状 |
| WS1 Retry-After 异常值（如 3600s） | >60s 直接放弃同模型重试、立即降级（决策 15 放弃阈值） | — |
| 公平 Semaphore 吞吐损耗（M2） | permit 分钟级、争用频次低，FIFO 收益（无饥饿）大于损耗；决策 10 记录取舍 | 改回非公平为单行改动 |

## 9. 验收标准

- [ ] **AC0（v1.2，WS0；v2.1 口径）**：前置门槛 = `docs/design/llm-client-stateless.md` §8 验收标准（AC1–AC12）全部达成——BYOK 经共享协议 + ResolvedEndpoint 生效、系统调用零变化、B4 断链钉死测试（chat + agent 双模式）由红转绿、`u:` 前缀全库清零、fallback 链可混合系统候选与 BYOK 选择、BYOK retry 落点（ByokChatCall 共享 RetryPolicy）明确落地。
- [ ] AC1：MockWebServer 流式 429 → `RateLimitedException`（携带 Retry-After ms，秒数与 HTTP-date 两形态）；5xx → `LLM_TRANSIENT_ERROR`；与阻塞路径映射一致。
- [ ] AC2：`RetryPolicyTest` 断言 jitter ∈ [0.5×, 1.5×) 计算延迟、RateLimitedException（≤60s）使用 Retry-After **原样延迟且不受 maxDelayMs 约束**、Retry-After >60s 判定不可重试直接降级；流式与阻塞退避同源；旧构造器用例不破坏。
- [ ] AC3：candidate params 可覆盖五类超时；未配置时行为与现状默认一致；`sharedOkHttpClient` 按 (connect, read, call) 缓存；**GenericChatClient 持双实例，流式 client 的 call 生效值 = stream-call-timeout-ms、阻塞 client = call-timeout-ms（wiring 断言）**。
- [ ] AC4：llm 模块内无 RestClient/JdkClientHttpRequestFactory 引用；4 客户端 MockWebServer 测试全绿；301 不跟随断言通过。
- [ ] AC5：并发 > max-concurrent 时，第 N+1 个请求在 acquire-timeout 后抛 `LLM_BUSY` 并降级到下一模型；取消的流释放 permit；**acquire 失败路径 permit 计数不变（决策 17）**；**permit 跨同模型重试持有（退避期间 inflight 恒 1）**；**interrupt 后中断标志还原且无 permit 泄漏**；**流式 acquire 等待期间不占任何阻塞线程（决策 16）**。
- [ ] AC6：流式降级（前一模型已发帧）时，前端先收 `event: reset`（from/to = modelCode）再收新模型内容；仅 reasoning 帧已发同样触发；零帧失败无 reset。
- [ ] AC7：`response_format` 等 5 个白名单参数经 Generic 路径进入请求体；非白名单键丢弃且每键首见 WARN。
- [ ] AC8：`llm.retry.attempts`、`llm.chat.ttft`、`llm.chat.tokens{operation=cache_hit}`（**含流式轮末包打点**）出现在 `/actuator/prometheus`；candidateId tag 无 `u:` 前缀。
- [ ] AC9：`app.chat.candidates` 死配置块删除（按块名定位，merge executor 配置保留），全量测试绿。
- [ ] AC10（v1.2 修订）：`LlmClientRegistry.refresh()` 后同 candidateId 且 config 未变 → 复用同一 AdmissionControl 实例（无超发）；config 任一字段变更 → 替换；**refresh() 移除候选 / destroy 后 `llm.inflight` gauge 从 MeterRegistry 移除（无僵尸序列，含熔断器 adapter 同步清理）**。
- [ ] AC11（v1.1 H1）：闸门初值经压测标定并按 §3.4.8 灰度启用，压测报告与灰度记录归档至本目录。

## 10. 后续优化项（本轮不做）

- **408/425 瞬态映射**（L2）：`translateStatus` 目前按"其余 4xx → LLM_STREAM_ERROR 不可重试"处理，与现状一致非回归；后续可将 408（超时）/425（过早）映射为 `LLM_TRANSIENT_ERROR`。
- **熔断 slow-call-rate**：熔断器仍无慢调用统计（"稳定但慢"模型不触发治理），待 TTFT 指标积累数据后评估。
- **bailian-sdk 协议归并**（自 llm-client-stateless v2.1 决策 2 移入）：DashScope SDK 客户端（`BailianChatClient`）无状态化并归并到 `ChatProtocol` 协议注册表，动态启用守卫（官方域 / `params.sdk-client` 覆盖）迁入协议选择——WS0 已修正事实：bailian chat 候选现行走 SDK 而非 GenericChatClient；待 WS1–WS8 稳定后另立工作流。
- ~~FallbackMeta candidateId 归一化~~（v1.2：随 WS0 消解，移出后续项）。

## 11. 架构参照：pi-ai 调研摘要

已随 WS0 迁至 `docs/design/llm-client-stateless.md` §9（含 Provider 纯数据描述子、调用时凭据解析、纯函数弹性、与本项目的场景差异记录）。本文引用其两处结论：决策 15 的 Retry-After 放弃阈值取自 `utils/provider-retry.ts` 的超限即抛语义；决策 13 的调用时凭据解析取自 `models.ts#applyAuth` 的请求级 key 覆盖模式。
