# 阻塞式聊天高并发改造设计

> 目标：在保持 `/api/v1/chat` 阻塞式接口契约不变的前提下，通过异步边界引入、热路径 I/O 解耦、
> 连接池调优和并发防护，将单节点阻塞式聊天吞吐能力从 ~20 req/s 提升到 200+ req/s。
>
> 核心约束：不迁移到 WebFlux，不改变 Controller 签名为 `Mono`/`Flux`，不引入新中间件。
> 充分利用项目已有的 `ScopedTasks` 结构化并发框架和 Redisson 分布式对象。

## 1. 现状分析

### 1.1 调用链路

```
ChatController.chat()                          ← POST /api/v1/chat
  └─ ChatServiceImpl.chat()                    ← 编排层：降级循环
       └─ doChat()                             ← 单次执行
            ├─ prepareContext()                ← userId / mode / model 路由
            ├─ buildExecutionContext()         ← 会话确保 + CAG 上下文
            │    ├─ conversationHelper.ensureConversationExists()  ← DB 同步
            │    └─ cagContextManager.buildContext()               ← 内存 + Redis
            ├─ modeStrategy.execute()          ← 策略执行
            │    ├─ buildAdvisorChain()
            │    ├─ ChatRequestSpecFactory.createSpec()  ← Caffeine / DB
            │    └─ spec.call().chatResponse()           ← LLM 同步 2-30s
            └─ processResult()
                 ├─ usageTracker.recordUsage()            ← DB 同步
                 └─ conversationHelper.saveMessagesAndNotify()  ← DB 事务 同步
```

### 1.2 线程占用模型

| 阶段 | 耗时 | 是否可异步 |
|------|------|-----------|
| Redis 限流 `RRateLimiter.tryAcquire()` | 1-5ms | 可本地化 |
| DB `ensureConversationExists` | 2-10ms | 可并行/预创建 |
| Caffeine/DB SystemPrompt + ModelParams | 0-50ms | 缓存命中后可忽略 |
| **LLM API 调用** | **2,000-30,000ms** | **核心异步化目标** |
| DB `saveMessagesAndNotify` (事务) | 5-20ms | 可异步解耦 |
| DB `recordUsage` | 2-10ms | 可异步解耦 |

**典型线程占用时间 = 2-30s，其中 LLM 调用占 95%+。**

Spring Boot 默认 200 个 Tomcat 线程，LLM 平均 10s 响应时，并发吞吐上限 ≈ 20 req/s。

### 1.3 已有基础设施

本项目已具备的结构化并发能力（`infrastructure/concurrent` 包）：

| 能力 | 入口 | 特性 |
|------|------|------|
| 作用域任务编排 | `ScopedTasks.open()` | fork/join/close 生命周期 |
| 虚拟线程执行器 | `ExecutorMode.VIRTUAL_THREAD_PER_TASK` | 默认模式，轻量级线程 |
| 策略化完成 | `ScopePolicy` | SHUTDOWN_ON_FAILURE/SUCCESS, COLLECT_ALL, QUORUM_SUCCESS 等 |
| 上下文传播 | `ContextCarrier` | MDC、SecurityContext 自动传播 |
| 可观测性 | `ScopeObserver` + `ScopeReport` | 作用域级耗时、成功/失败计数 |

Redis/Redisson 分布式对象已在项目中使用：

| 对象 | 使用位置 | 用途 |
|------|----------|------|
| `RRateLimiter` | `FallbackRateLimiter` | 分布式限流 |
| `RQueue` | `MessageDeadLetterQueue` | 死信队列 |
| `RMapCache` | `ModelHealthCache` | 带 TTL 的模型健康缓存 |
| `RLock` | `EtlDispatchServiceImpl` | 分布式锁 |

## 2. 设计目标

### 2.1 目标

- 将阻塞式聊天的单节点并发吞吐从 ~20 req/s 提升到 200+ req/s（10x 提升）。
- Controller 接口签名不变（仍返回 `GlobalResponse<ChatResponse>`），客户端零感知。
- 利用 `ScopedTasks` + 虚拟线程释放 Tomcat 线程，避免全量迁移 WebFlux。
- 热路径 I/O（usage 记录、消息持久化）异步解耦，不阻塞响应返回。
- 降级链从串行尝试改为并行探测，缩短最坏情况延迟。
- 连接池、线程池参数可配置化，按负载调优。

### 2.2 非目标

- 不迁移到 Spring WebFlux 或改变 Controller 为 reactive 签名。
- 不引入新的消息中间件（Kafka、RabbitMQ）。
- 不改造流式聊天路径（`/chat/stream`），流式路径已使用 `Flux<String>`。
- 不改变 `ChatService` 接口的阻塞语义（`ChatResponse chat(ChatRequest)` 签名保持不变）。
- 不在 Phase 1 实现跨节点的请求调度或负载均衡。

## 3. 设计原则

1. **渐进式改造**：分 4 个 Phase 迭代，每个 Phase 可独立上线、独立回滚。
2. **接口不变**：外部契约不变，改造仅影响内部实现。
3. **优先利用已有能力**：`ScopedTasks`、`ContextCarrier`、Redisson 均已有成熟实现，不重复造轮子。
4. **降级优先**：每层异步化都保留同步 fallback 路径，异步组件故障时自动降级。

## 4. Phase 迭代路线

### Phase 1 — 基础设施调优 + 异步解耦

**目标**：零代码改动或最小改动，通过配置和旁路异步化实现 2-3x 吞吐提升。

**退出条件**：Tomcat 线程池、OkHttp 连接池配置生效；UsageTracker 异步写入不影响响应延迟。

#### 4.1.1 Tomcat 线程池调优

```yaml
# application.yml
server:
  tomcat:
    threads:
      max: 400          # 默认 200，LLM 长阻塞场景需加倍
      min-spare: 20     # 预热最小线程数
    max-connections: 8192
    accept-count: 200   # 等待队列，超出返回 503
    connection-timeout: 20s
```

#### 4.1.2 OkHttpClient 连接池配置

当前 `ModelProviderAutoConfiguration` 中 `OkHttpClient` 使用默认连接池（5 idle / 64 max per host）。

```java
// ModelProviderAutoConfiguration.java
@Bean
Call.Factory okHttpCallFactory() {
    return new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .connectionPool(new ConnectionPool(
            20,              // max idle connections
            5, TimeUnit.MINUTES  // keep-alive
        ))
        .dispatcher(new Dispatcher() {{
            setMaxRequests(200);
            setMaxRequestsPerHost(128);
        }})
        .build();
}
```

#### 4.1.3 ChatUsageTracker 异步化

将 `processResult()` 中的同步 usage 记录改为 Spring 事件驱动：

```java
// 新增事件
public record UsageRecordEvent(
    String conversationId,
    String modelId,
    @Nullable ChatResponse springAiResponse,
    long elapsedMs
) {}

// ChatUsageTracker 改为事件监听器
@Component
public class ChatUsageTracker {

    @Async("usageExecutor")
    @EventListener
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)
    public void onUsageRecord(UsageRecordEvent event) {
        // 原有 recordUsage 逻辑
    }
}

// ChatServiceImpl.processResult() 改为发布事件
private ChatResponse processResult(...) {
    eventPublisher.publishEvent(new UsageRecordEvent(
        ctx.conversationId(), compositeModelId,
        result.springAiResponse(), ctx.elapsed()));
    // 不等待 usage 写入完成
}
```

需要配置专用线程池：

```java
@Bean("usageExecutor")
Executor usageExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
}
```

#### 4.1.4 ChatConversationHelper 消息保存异步化

`saveMessagesAndNotify()` 从同步事务改为异步队列：

```java
// 新增事件
public record MessageSaveEvent(
    String conversationId,
    String userMessage,
    String assistantContent,
    String modelId,
    @Nullable ChatResponse springAiResponse,
    long elapsedMs
) {}

// ConversationHelper 异步监听
@Async("conversationExecutor")
@EventListener
public void onMessageSave(MessageSaveEvent event) {
    // 原有 saveMessagesAndNotify 事务逻辑
}
```

**风险**：消息保存失败时，客户端已收到响应。缓解措施：
- 保留死信队列 `MessageDeadLetterQueue`（已有 Redis `RQueue` 实现）兜底。
- 异步保存失败时写入 DLQ，由 `DeadLetterRetryScheduler`（已有）重试。

### Phase 2 — Controller 异步化 + ScopedTasks

**目标**：将 LLM 调用从 Tomcat 线程迁移到虚拟线程，释放 Servlet 线程。

**退出条件**：`/chat` 接口返回 `DeferredResult`，Tomcat 线程在 LLM 调用前释放；压测验证线程占用时间 < 50ms。

#### 4.2.1 Controller 返回 DeferredResult

```java
// ChatController.java
@PostMapping("/chat")
public DeferredResult<GlobalResponse<ChatResponse>> chat(@Valid @RequestBody ChatRequest request) {
    DeferredResult<GlobalResponse<ChatResponse>> deferred = new DeferredResult<>(30_000L);

    try (TaskScope scope = scopedTasks.open("blocking-chat",
            ScopeOptions.builder("blocking-chat")
                .policy(ScopePolicy.SHUTDOWN_ON_FAILURE)
                .defaultTimeout(Duration.ofSeconds(30))
                .inheritMdc(true)
                .inheritSecurityContext(true)
                .build())) {

        scope.fork("llm-call", () -> {
            ChatResponse response = chatService.chat(request);
            deferred.setResult(GlobalResponse.ok(response));
            return response;
        });

        scope.join();
    } catch (ScopeTimeoutException e) {
        deferred.setErrorResult(GlobalResponse.fail("请求超时，请稍后重试"));
    } catch (Exception e) {
        deferred.setErrorResult(GlobalResponse.fail(e.getMessage()));
    }

    return deferred;
}
```

**关键变化**：
- Tomcat 线程仅执行 `prepareContext` 级别的工作（< 50ms），然后通过 `DeferredResult` 释放。
- LLM 调用在虚拟线程中执行，不占用 Tomcat 线程池。
- `ChatService.chat()` 签名和实现**完全不变**，ScopedTasks 仅包裹在 Controller 层。

#### 4.2.2 为什么不改 ChatService 签名

`ChatService.chat(ChatRequest)` 返回 `ChatResponse` 是一个干净的同步 API：
- 可被其他内部服务同步调用（如 Agent 模式）。
- 可在 ScopedTasks 中作为 Callable 直接 fork。
- 改为 `CompletableFuture<ChatResponse>` 会迫使所有调用方适配异步 API。

异步边界放在 Controller 层是侵入最小的方案。

#### 4.2.3 DeferredResult 超时处理

```java
deferred.onTimeout(() -> {
    log.warn("Chat request timed out after 30s");
    // scope 的 close() 会自动取消未完成的虚拟线程
});
```

`ScopePolicy.SHUTDOWN_ON_FAILURE` + `defaultTimeout(30s)` 提供双重保护：
- LLM 调用超时 → `ScopeTimeoutException` → deferred 返回超时响应。
- LLM 调用异常 → policy 触发 cancel → 其余子任务（如有）被取消。

### Phase 3 — 降级链并行探测

**目标**：将串行降级改为并行探测（类似流式路径的 probe stream 模式），最坏情况延迟从 `n × 超时` 降到 `1 × 超时`。

**退出条件**：降级链 3 个候选模型时，最坏情况延迟不超过 1.5 × 单次超时。

#### 4.3.1 并行降级策略

利用 `ScopePolicy.SHUTDOWN_ON_SUCCESS` 实现 "第一个成功即返回"：

```java
// ChatServiceImpl 新增方法
private ChatResponse doChatParallel(String requestedModel, List<String> chain) {
    try (TaskScope scope = scopedTasks.open("parallel-fallback",
            ScopeOptions.builder("parallel-fallback")
                .policy(ScopePolicy.SHUTDOWN_ON_SUCCESS)
                .defaultTimeout(Duration.ofSeconds(30))
                .maxConcurrency(chain.size())
                .build())) {

        for (int i = 0; i < chain.size(); i++) {
            String candidateModel = chain.get(i);
            boolean isFallback = i > 0;

            if (!circuitBreakers.isCallAllowed(candidateModel)) {
                continue;
            }

            scope.fork("model-" + candidateModel, () -> {
                ChatRequest candidateRequest = isFallback
                    ? request.withModel(candidateModel) : request;
                FallbackMeta meta = isFallback
                    ? new FallbackMeta(requestedModel, true) : null;
                ChatResponse response = doChat(candidateRequest, meta);
                circuitBreakers.recordSuccess(candidateModel);
                return new FallbackResult(response, candidateModel);
            });
        }

        scope.join();

        FallbackResult best = scope.subtasks().stream()
            .filter(Subtask::succeeded)
            .map(s -> (FallbackResult) s.result())
            .findFirst()
            .orElseThrow(() -> new ProviderNotFoundException(requestedModel,
                "所有模型均不可用"));

        // 对未成功的候选记录熔断
        scope.subtasks().stream()
            .filter(Subtask::failed)
            .forEach(s -> circuitBreakers.recordFailure(extractModelId(s)));

        return best.response();
    }
}
```

#### 4.3.2 串行/并行策略选择

通过配置控制降级策略，支持渐进式切换：

```yaml
app:
  chat:
    fallback:
      parallel-enabled: false   # Phase 3 默认关闭
      parallel-max-probes: 3    # 最大并行探测数
```

```java
// ChatServiceImpl.chat()
if (fallbackProperties.parallelEnabled()) {
    return doChatParallel(request.model(), chain);
} else {
    return doChatSequential(request.model(), chain);  // 现有串行逻辑
}
```

#### 4.3.3 并行降级注意事项

- **Token 浪费**：并行探测会同时调用多个模型，产生额外 token 消耗。可通过 `maxConcurrency` 限制同时探测数（如先并行 2 个，失败后再探测第 3 个）。
- **熔断器兼容**：并行场景下多个候选同时检查 `isCallAllowed()`，与串行场景语义一致。
- **上下文传播**：`ScopedTasks` 已内置 `ContextCarrier` 机制，MDC 和 SecurityContext 自动传播到每个 fork 的虚拟线程。

### Phase 4 — 限流器本地化 + 指标接入

**目标**：消除热路径 Redis RTT；接入 Micrometer 指标实现可观测性。

**退出条件**：限流检查耗时从 1-5ms 降到 < 0.1ms；Grafana 可观测线程池和 LLM 调用指标。

#### 4.4.1 限流器本地化

项目已有 `TokenBucketLimiter`（本地令牌桶）和 `FallbackRateLimiter`（Redis → 本地降级）。
将默认策略从 "Redis 优先" 改为 "本地优先 + 定期同步"：

```java
// FallbackRateLimiter 策略调整
public boolean tryAcquire(String modelId) {
    // 优先本地令牌桶（无网络开销）
    if (localBucket.tryAcquire(modelId)) {
        return true;
    }
    // 本地耗尽时才查 Redis（作为补充配额来源）
    if (redissonAvailable) {
        return redisRateLimiter.tryAcquire(modelId);
    }
    return false;
}
```

配额同步机制：通过 Redisson `RMapCache` 定期将 Redis 配额快照同步到本地令牌桶（类似 `ModelHealthCache` 的 TTL 模式）。

#### 4.4.2 Micrometer 指标接入

```java
// 自定义指标注册
@Bean
MeterBinder chatMetrics() {
    return registry -> {
        // Tomcat 线程池指标（Spring Boot 自动注册）
        // OkHttpClient 连接池指标
        registry.gauge("okhttp.pool.idle", connectionPool, Pool::idleConnectionCount);
        registry.gauge("okhttp.pool.total", connectionPool, Pool::connectionCount);

        // LLM 调用耗时分布
        Timer.builder("chat.llm.duration")
            .tag("endpoint", "blocking")
            .publishPercentileHistogram()
            .register(registry);

        // 降级链指标
        Counter.builder("chat.fallback.invocations")
            .tag("reason", "model-failure")
            .register(registry);
    };
}
```

通过 `ScopeObserver` 自动采集作用域级指标：

```java
@Component
public class MetricsScopeObserver implements ScopeObserver {
    private final MeterRegistry registry;

    @Override
    public void onScopeClosed(ScopeReport report) {
        Timer.builder("scoped-tasks.scope")
            .tag("name", report.scopeName())
            .register(registry)
            .record(report.elapsed());
    }
}
```

## 5. 改动文件清单

| 文件 | Phase | 动作 | 说明 |
|------|-------|------|------|
| `application.yml` | 1 | 修改 | 新增 Tomcat 线程池配置 |
| `ModelProviderAutoConfiguration.java` | 1 | 修改 | OkHttpClient 连接池 + Dispatcher 配置 |
| `ChatUsageTracker.java` | 1 | 修改 | 改为 `@EventListener` 异步消费 |
| `UsageRecordEvent.java` | 1 | 新增 | usage 记录事件 |
| `ChatConversationHelper.java` | 1 | 修改 | 消息保存改为异步事件 |
| `MessageSaveEvent.java` | 1 | 新增 | 消息保存事件 |
| `AsyncExecutorConfig.java` | 1 | 新增 | `usageExecutor` + `conversationExecutor` 虚拟线程池 |
| `ChatController.java` | 2 | 修改 | 返回 `DeferredResult`，包裹 ScopedTasks |
| `ChatController.java` | 2 | 修改 | 注入 `ScopedTasks` |
| `ChatServiceImpl.java` | 3 | 修改 | 新增 `doChatParallel()` 方法 |
| `ChatFallbackProperties.java` | 3 | 修改 | 新增 `parallelEnabled` / `parallelMaxProbes` 字段 |
| `FallbackResult.java` | 3 | 新增 | 并行降级结果包装 record |
| `FallbackRateLimiter.java` | 4 | 修改 | 策略调整为本地优先 |
| `MetricsScopeObserver.java` | 4 | 新增 | ScopeObserver 指标采集 |
| `ChatMetricsConfig.java` | 4 | 新增 | Micrometer 指标注册 |

## 6. 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 消息异步保存失败导致丢失 | 中 | DLQ 兜底 + `DeadLetterRetryScheduler` 重试（已有）；Phase 1 仅对 usage 先异步，消息保存保持同步 |
| `DeferredResult` 超时但 LLM 调用仍在进行 | 中 | `TaskScope.close()` 自动 cancel 虚拟线程；OkHttp `callTimeout` 作为底层兜底 |
| 并行降级产生额外 token 消耗 | 中 | `parallelMaxProbes` 限制最大并行数；配置开关默认关闭 |
| 虚拟线程与 `synchronized` 锁定（pinning） | 低 | 项目已使用 `ReentrantLock`（`TokenBucketLimiter`）；OkHttp 已使用 `Call.Factory` 接口 |
| 异步事件的线程池资源耗尽 | 低 | 虚拟线程无固定上限；`@EventListener` 异常不传播到调用方 |
| Micrometer 指标基数爆炸 | 低 | 使用固定 tag 值（scope name, endpoint），不使用用户/模型 ID 作为 tag |

## 7. 参考资料

- 项目内部：`docs/design/structured-concurrency-scope.md` — ScopedTasks 设计
- 项目内部：`infrastructure/concurrent/` — 结构化并发实现
- Spring Framework：`DeferredResult` 非阻塞异步处理
- JDK 21：`Executors.newVirtualThreadPerTaskExecutor()` 虚拟线程
- Redisson：`RRateLimiter` / `RQueue` / `RMapCache` 分布式对象
