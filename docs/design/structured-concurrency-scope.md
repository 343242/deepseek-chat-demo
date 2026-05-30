# JDK 21 稳定 API 下的结构化并发工具设计

> 目标：不启用 JDK 预览特性，不修改当前 JVM 启动参数，在 JDK 21 稳定 API 上实现一个适配本项目的结构化并发风格工具。
>
> 该工具不是直接复刻 JDK 预览 API，而是抽取结构化并发对本项目有价值的语义：父子任务生命周期、统一等待、失败传播、自动取消、上下文传递和可观测性。

## 1. 背景

JDK 21 中虚拟线程已经稳定，可以通过 `Executors.newVirtualThreadPerTaskExecutor()` 使用；但结构化并发仍是预览特性，直接使用需要启用 preview 编译和运行参数。当前项目不能修改 JVM 运行方式，因此不应直接依赖预览 API。

项目内已经存在多处并发编排：

| 位置 | 当前模式 | 主要问题 |
|------|----------|----------|
| `HybridSearchService` | `CompletableFuture.supplyAsync()` 并行向量/BM25 检索 | 失败、取消、上下文传播分散 |
| `ModelRegistryRefresher` | `CompletableFuture` + 手写 MDC 恢复 | MDC 传递重复实现 |
| `SandboxService` | `ExecutorService` + 手写 MDC 恢复 | 作用域生命周期不统一 |
| `StandardStrategy` / `FastTrackStrategy` | 多阶段 `CompletableFuture.allOf()` | 异常聚合和取消语义不直观 |
| `DatasetGenerator` | 自建线程池 + `CompletableFuture` | 并发上限、关闭、异常传播需要统一 |

这些场景并不都应该立即迁移，但它们说明项目需要一个统一的“请求内并发编排”抽象，减少重复代码和语义漂移。

## 2. 设计目标

### 2.1 目标

- 提供类似结构化并发的作用域 API：所有子任务必须挂在父作用域下。
- 父作用域退出时，自动取消未完成任务。
- 支持等待全部、超时等待、失败即取消、成功即取消、全部收集等策略。
- 支持 MDC、请求上下文、安全上下文等 ThreadLocal 类上下文的显式传递。
- 支持任务命名、耗时记录、失败聚合和可观测性。
- 只使用 JDK 21 稳定 API 和项目已有 Spring / SLF4J 能力。
- 不新增第三方依赖。

### 2.2 非目标

- 不承诺 100% 兼容 JDK 预览 `StructuredTaskScope` API。
- 不通过反射读取或修改 JVM 内部 `ThreadLocalMap`。
- 不强制杀死不响应中断的任务。
- 不替代所有 `CompletableFuture`。长期后台任务、事件驱动链路、缓存预热、无父请求生命周期的异步任务仍可继续使用现有模型。
- 不在第一阶段接管 Reactor `Flux` 的完整流式生命周期。

## 3. 设计原则

### 3.1 结构化生命周期

并发任务不能脱离父调用栈独立存在。业务代码通过 `try-with-resources` 创建作用域，作用域内 `fork` 的任务由作用域统一管理：

```java
try (TaskScope scope = scopedTasks.open("hybrid-search")) {
    Subtask<List<ScoredDocument>> vector = scope.fork("vector-search", () -> vectorSearch(query));
    Subtask<List<ScoredDocument>> bm25 = scope.fork("bm25-search", () -> bm25Search(query));

    scope.joinUntil(Duration.ofSeconds(3));
    scope.throwIfFailed();

    return merge(vector.result(), bm25.result());
}
```

调用方看到的是同步风格代码，实际并发由 scope 内部处理。这样比直接暴露多个 `CompletableFuture` 更容易保证：

- 子任务不会忘记等待。
- 失败不会被隐藏在异步链尾部。
- 请求结束时不会残留任务。
- 上下文传播和清理在一个地方完成。

### 3.2 显式上下文传播

参考 `TransmittableThreadLocal` 的核心思想：不要要求业务任务关心上下文传递，而是在任务提交前捕获父线程上下文，在子线程执行时恢复，执行结束后还原。

本设计不尝试“自动发现所有 ThreadLocal”，而是采用白名单式 `ContextCarrier`。原因是 JDK 没有稳定公开 API 可以安全枚举所有 ThreadLocal；反射读取内部结构会引入 JVM 兼容风险，也违背本设计“不修改 JVM、不依赖非稳定能力”的目标。

## 4. 核心抽象

### 4.1 ScopedTasks

`ScopedTasks` 是业务入口门面，负责创建 `TaskScope`。

```java
public interface ScopedTasks {

    TaskScope open(String name);

    TaskScope open(String name, ScopePolicy policy);

    TaskScope open(String name, ScopeOptions options);
}
```

推荐实现类：

```text
com.smart.rag.common.concurrent.DefaultScopedTasks
```

### 4.2 TaskScope

`TaskScope` 是结构化并发作用域，生命周期由 `try-with-resources` 管理。

```java
public interface TaskScope extends AutoCloseable {

    <T> Subtask<T> fork(String name, Callable<T> task);

    void join();

    void joinUntil(Duration timeout);

    void throwIfFailed();

    List<Subtask<?>> subtasks();

    @Override
    void close();
}
```

契约：

- `fork()` 只能在 scope 未关闭时调用。
- `join()` 等待当前已注册任务完成。
- `joinUntil()` 超时后取消未完成任务，并抛出项目内异常。
- `throwIfFailed()` 将已记录失败聚合后抛出。
- `close()` 必须取消所有未完成任务并释放资源。

### 4.3 Subtask

`Subtask` 是业务可见的子任务句柄，封装底层 `Future`。

```java
public interface Subtask<T> {

    String name();

    TaskState state();

    T result();

    Throwable exception();

    boolean cancel();
}
```

`TaskState`：

```java
public enum TaskState {
    NEW,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}
```

业务层不直接持有 `Future`，避免绕开 scope 的生命周期管理。

### 4.4 ScopeOptions

```java
public record ScopeOptions(
    String name,
    ScopePolicy policy,
    ExecutorMode executorMode,
    int maxConcurrency,
    Duration defaultTimeout,
    boolean inheritMdc,
    boolean inheritSecurityContext,
    boolean inheritRequestContext
) {
    public static ScopeOptions shutdownOnFailure(String name) {
        return new ScopeOptions(
            name,
            ScopePolicy.SHUTDOWN_ON_FAILURE,
            ExecutorMode.VIRTUAL_THREAD_PER_TASK,
            0,
            Duration.ZERO,
            true,
            true,
            true
        );
    }
}
```

`maxConcurrency = 0` 表示不在 scope 层限流，由调用方或底层资源控制。对外部模型调用、RAG 检索、工具调用等场景，可配置正数并发上限。

## 5. 策略语义

### 5.1 ScopePolicy

```java
public enum ScopePolicy {
    SHUTDOWN_ON_FAILURE,
    SHUTDOWN_ON_SUCCESS,
    COLLECT_ALL
}
```

| 策略 | 语义 | 适用场景 |
|------|------|----------|
| `SHUTDOWN_ON_FAILURE` | 任一任务失败后取消其它未完成任务 | 请求内多个必要子任务并行 |
| `SHUTDOWN_ON_SUCCESS` | 任一任务成功后取消其它未完成任务 | 多数据源竞速、fallback 竞速 |
| `COLLECT_ALL` | 等待全部结束，不自动 fail-fast | 批量评估、ETL 多候选处理 |

第一阶段建议默认使用 `SHUTDOWN_ON_FAILURE`，因为 RAG 请求内并行子任务通常是共同构成最终结果的必要步骤。

### 5.2 策略模式

`TaskScope` 不直接硬编码所有完成策略，而是委托给策略处理器：

```java
interface ScopePolicyHandler {

    void onSuccess(SubtaskInternal<?> task, ScopeState state);

    void onFailure(SubtaskInternal<?> task, Throwable error, ScopeState state);

    boolean shouldStop(ScopeState state);
}
```

实现类：

```text
ShutdownOnFailurePolicy
ShutdownOnSuccessPolicy
CollectAllPolicy
```

后续如果出现“至少 N 个任务成功即可返回”的需求，可以新增 `QuorumSuccessPolicy`，而不修改 `DefaultTaskScope` 主流程。

## 6. 上下文传递设计

### 6.1 ContextCarrier

```java
public interface ContextCarrier<S> {

    S capture();

    S restore(S snapshot);

    void clear(S previous);
}
```

语义：

- `capture()`：在父线程提交任务前捕获上下文快照。
- `restore()`：在子线程执行任务前恢复快照，并返回子线程原上下文。
- `clear()`：任务结束后恢复子线程原上下文，防止线程复用导致污染。

### 6.2 MDC 示例

```java
public final class MdcContextCarrier implements ContextCarrier<Map<String, String>> {

    @Override
    public Map<String, String> capture() {
        return MDC.getCopyOfContextMap();
    }

    @Override
    public Map<String, String> restore(Map<String, String> snapshot) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (snapshot == null || snapshot.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(snapshot);
        }
        return previous;
    }

    @Override
    public void clear(Map<String, String> previous) {
        if (previous == null || previous.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
```

### 6.3 ContextSnapshot

```java
public final class ContextSnapshot {

    private final List<CapturedContext<?>> contexts;

    public static ContextSnapshot capture(List<ContextCarrier<?>> carriers) {
        // 逐个 carrier 捕获父线程上下文。
    }

    public ContextRestorer restore() {
        // 逐个 carrier 恢复子线程上下文，返回 closeable restorer。
    }
}
```

`ContextRestorer` 使用 `AutoCloseable`：

```java
try (ContextRestorer ignored = snapshot.restore()) {
    return delegate.call();
}
```

### 6.4 装饰器模式

上下文传递通过装饰原始任务完成：

```java
final class ContextAwareCallable<T> implements Callable<T> {

    private final Callable<T> delegate;
    private final ContextSnapshot snapshot;

    @Override
    public T call() throws Exception {
        try (ContextRestorer ignored = snapshot.restore()) {
            return delegate.call();
        }
    }
}
```

任务提交链路：

```text
业务 Callable
    -> ContextAwareCallable
    -> ObservedCallable
    -> FutureTask / ExecutorService
```

这与 `TransmittableThreadLocal` 的设计思想一致：不改变业务任务本身，而是在任务和线程池之间增加一层包装，让原始 `ThreadLocal` 能跨线程传递。

差异是：本项目工具采用显式 carrier 白名单，而不是扩展 ThreadLocal 类型本身。这样更符合项目约束，也更容易做安全审计。

## 7. Executor 设计

### 7.1 ExecutorMode

```java
public enum ExecutorMode {
    VIRTUAL_THREAD_PER_TASK,
    PLATFORM_THREAD_POOL,
    SHARED_EXECUTOR
}
```

推荐默认：

- IO 密集型请求内任务：`VIRTUAL_THREAD_PER_TASK`
- CPU 密集型任务：`PLATFORM_THREAD_POOL`
- 已由 Spring 管理的专用线程池：`SHARED_EXECUTOR`

### 7.2 工厂模式

```java
interface ScopeExecutorFactory {

    ExecutorService create(ScopeOptions options);
}
```

默认实现：

```java
public final class DefaultScopeExecutorFactory implements ScopeExecutorFactory {

    @Override
    public ExecutorService create(ScopeOptions options) {
        return switch (options.executorMode()) {
            case VIRTUAL_THREAD_PER_TASK -> Executors.newVirtualThreadPerTaskExecutor();
            case PLATFORM_THREAD_POOL -> createBoundedPlatformPool(options);
            case SHARED_EXECUTOR -> sharedExecutor(options);
        };
    }
}
```

如果使用 `newVirtualThreadPerTaskExecutor()`，scope 关闭时应关闭该 executor。若使用 Spring 注入的共享 executor，scope 只能取消任务，不能关闭共享 executor。

## 8. TaskScope 执行流程

### 8.1 fork

```text
1. 校验 scope 未关闭。
2. 捕获父线程上下文 ContextSnapshot。
3. 用 ContextAwareCallable 装饰业务 Callable。
4. 用 ObservedCallable 装饰任务，记录任务名、状态、耗时和异常。
5. 如配置 maxConcurrency，则用 Semaphore 控制并发进入。
6. 提交到 ExecutorService。
7. 创建 DefaultSubtask 并注册到 ScopeState。
```

### 8.2 join

```text
1. 读取当前 scope 已注册任务快照。
2. 等待任务完成。
3. 每个任务完成后更新状态。
4. 成功任务记录 result。
5. 失败任务记录 Throwable。
6. 调用 ScopePolicyHandler 判断是否需要取消其它任务。
7. 如果策略要求提前停止，则 cancel 未完成任务。
```

### 8.3 joinUntil

```text
1. 计算 deadline。
2. 对所有任务按剩余时间等待。
3. 超时后取消未完成任务。
4. 抛出 ScopeTimeoutException。
5. 保留已失败任务作为 suppressed，便于排查。
```

### 8.4 close

```text
1. 标记 scope closing。
2. 取消所有 NEW/RUNNING 状态任务。
3. 如果 executor 由 scope 创建，则 shutdown。
4. 清理内部任务列表和上下文引用。
5. 多次 close 必须幂等。
```

## 9. 异常模型

项目规范要求业务异常统一，工具层异常建议放在 `com.smart.rag.exception` 或 `com.smart.rag.common.concurrent` 下，并最终可由 `GlobalExceptionHandler` 转换。

```java
public class ScopeExecutionException extends RuntimeException {

    public ScopeExecutionException(String scopeName, Throwable firstFailure, List<Throwable> failures) {
        super("并发作用域执行失败: " + scopeName, firstFailure);
        failures.stream()
            .filter(failure -> failure != firstFailure)
            .forEach(this::addSuppressed);
    }
}
```

建议异常：

| 异常 | 场景 |
|------|------|
| `ScopeExecutionException` | 子任务失败聚合 |
| `ScopeTimeoutException` | `joinUntil` 超时 |
| `ScopeClosedException` | 对已关闭 scope 调用 `fork` |
| `SubtaskNotCompletedException` | 在任务未成功时读取 `result()` |

异常消息面向内部开发者；如果穿透到接口层，应由业务层转换为用户友好的 `BusinessException` 或统一错误响应。

## 10. 可观测性

`ObservedCallable` 负责记录任务级指标：

```java
final class ObservedCallable<T> implements Callable<T> {

    private final String scopeName;
    private final String taskName;
    private final Callable<T> delegate;
    private final SubtaskInternal<T> subtask;

    @Override
    public T call() throws Exception {
        long start = System.nanoTime();
        subtask.markRunning();
        try {
            T result = delegate.call();
            subtask.markSuccess(result, elapsed(start));
            return result;
        } catch (Throwable error) {
            subtask.markFailed(error, elapsed(start));
            throw error;
        }
    }
}
```

日志建议：

- `DEBUG`：任务开始、完成、取消。
- `WARN`：任务超时、任务取消失败、子任务失败但被策略吸收。
- `ERROR`：scope 级不可恢复失败。

不要在循环内打 INFO 以上日志；不要记录用户输入全文、Token、Cookie、完整 prompt 等敏感内容。

## 11. 与 CompletableFuture 的关系

### 11.1 适合迁移

适合迁移的代码通常符合以下特征：

- 多个子任务属于同一个请求生命周期。
- 调用方需要等待这些子任务后才能继续。
- 任一必要任务失败时，其它任务继续执行没有意义。
- 需要传递 MDC / traceId / 安全上下文。
- 需要统一超时和取消。

示例：

```java
CompletableFuture<A> a = CompletableFuture.supplyAsync(this::loadA);
CompletableFuture<B> b = CompletableFuture.supplyAsync(this::loadB);

CompletableFuture.allOf(a, b).join();
return combine(a.join(), b.join());
```

可迁移为：

```java
try (TaskScope scope = scopedTasks.open("load-and-combine")) {
    Subtask<A> a = scope.fork("load-a", this::loadA);
    Subtask<B> b = scope.fork("load-b", this::loadB);

    scope.joinUntil(Duration.ofSeconds(3));
    scope.throwIfFailed();

    return combine(a.result(), b.result());
}
```

### 11.2 不适合迁移

- Spring 启动后的后台异步初始化。
- 不需要父调用方等待的 fire-and-forget 任务。
- Reactor 流式链路中依赖背压和订阅生命周期的任务。
- 已经由专用队列、调度器、消息系统管理生命周期的任务。

这些场景应继续使用现有模型，或单独设计后台任务管理器。

## 12. 与流式响应的边界

`executeStream`、SSE、Reactor `Flux` 等流式场景不能简单包进一个短生命周期 `TaskScope`。原因：

- 流式响应的生命周期由订阅方控制，不等同于方法调用返回。
- 取消信号来自 Reactor subscription，不一定等于 Java 线程中断。
- 背压、partial content 保存、usage 记录需要依赖 `doFinally` 等 Reactor 钩子。

因此第一阶段建议：

- 只在流式响应的前置准备阶段使用 `TaskScope`，例如并行加载配置、上下文、工具元数据。
- 不用 `TaskScope` 包住整个 `Flux` 生成过程。
- 如需深度整合 Reactor，应另开设计文档，明确 subscription 和 scope 的绑定关系。

## 13. 建议包结构

```text
src/main/java/com/smart/rag/common/concurrent/
├── ScopedTasks.java
├── TaskScope.java
├── Subtask.java
├── TaskState.java
├── ScopePolicy.java
├── ScopeOptions.java
├── ExecutorMode.java
├── DefaultScopedTasks.java
├── DefaultTaskScope.java
├── DefaultSubtask.java
├── ScopeExecutionException.java
├── ScopeTimeoutException.java
├── ScopeClosedException.java
└── SubtaskNotCompletedException.java

src/main/java/com/smart/rag/common/concurrent/context/
├── ContextCarrier.java
├── ContextSnapshot.java
├── ContextRestorer.java
├── ContextAwareCallable.java
├── MdcContextCarrier.java
├── RequestContextCarrier.java
└── SecurityContextCarrier.java

src/main/java/com/smart/rag/common/concurrent/policy/
├── ScopePolicyHandler.java
├── ShutdownOnFailurePolicy.java
├── ShutdownOnSuccessPolicy.java
└── CollectAllPolicy.java

src/main/java/com/smart/rag/config/
└── ScopedTaskAutoConfiguration.java
```

放在 `common/concurrent` 的原因：这是跨 RAG、ETL、模型刷新、沙箱执行都可能复用的基础设施，不属于某个业务模块。

## 14. 设计模式总结

| 模式 | 应用位置 | 目的 |
|------|----------|------|
| 门面模式 | `ScopedTasks` | 给业务层提供简单入口，隐藏 executor、策略、上下文传递细节 |
| 装饰器模式 | `ContextAwareCallable`、`ObservedCallable` | 在不改业务任务的前提下增加上下文传播、状态记录、耗时统计 |
| 策略模式 | `ScopePolicyHandler` | 将失败/成功/收集语义从 scope 主流程中剥离 |
| 工厂模式 | `ScopeExecutorFactory` | 根据配置创建虚拟线程、平台线程池或共享 executor |
| 模板方法思想 | `DefaultTaskScope` 的 fork/join/close 生命周期 | 固定生命周期骨架，策略只定制关键节点行为 |
| 组合复用 | `DefaultTaskScope` 组合 executor、policy、context carriers | 避免继承膨胀，符合项目质量规范 |

## 15. 第一阶段实现范围

建议第一阶段只实现最小闭环：

- `ScopedTasks.open(String name)`
- `SHUTDOWN_ON_FAILURE`
- `COLLECT_ALL`
- `join()` / `joinUntil(Duration)`
- `throwIfFailed()`
- `try-with-resources` 自动取消
- MDC 上下文传递
- 虚拟线程 executor
- 单元测试覆盖核心语义

暂缓：

- `SHUTDOWN_ON_SUCCESS`
- Spring Security 上下文传递
- RequestContextHolder 传递
- Micrometer 指标
- 与 Reactor 流式深度整合
- 自动迁移所有 `CompletableFuture`

## 16. 测试计划

### 16.1 单元测试

必须覆盖：

- 所有任务成功时，`result()` 正确返回。
- 任一任务失败时，`SHUTDOWN_ON_FAILURE` 取消其它未完成任务。
- `COLLECT_ALL` 不因单个失败提前取消。
- `joinUntil` 超时后取消未完成任务。
- `close` 对未完成任务执行取消。
- `close` 幂等。
- 对已关闭 scope 调用 `fork` 抛出异常。
- 未完成或失败任务调用 `result()` 抛出异常。
- MDC 在子任务中可见，任务结束后不污染执行线程。
- 多个失败任务通过 suppressed 聚合。

### 16.2 集成测试

第一批集成测试建议围绕 `HybridSearchService` 或一个小型测试服务：

- 并行任务成功合并结果。
- 一个检索分支失败时另一个分支被取消。
- traceId 在并行任务日志中保持一致。
- 超时配置生效。

### 16.3 回归测试

迁移每一个现有 `CompletableFuture` 使用点时，都应先补齐当前行为测试，再替换实现。尤其注意：

- 异常类型是否改变。
- 超时时间是否改变。
- 是否从“等待全部”变成“失败即取消”。
- 是否影响调用方对 partial result 的处理。

## 17. 迁移策略

### 17.1 候选顺序

建议按风险从低到高迁移：

1. `HybridSearchService`：请求内两个检索分支并行，最贴近结构化并发模型。
2. `ModelRegistryRefresher`：主要收益是复用 MDC 传递，但它是后台刷新任务，需谨慎判断是否适合 scope 生命周期。
3. `SandboxService`：已有虚拟线程和 MDC 手写逻辑，可用 context 装饰器减少重复。
4. `StandardStrategy` / `FastTrackStrategy`：ETL 多阶段并发较复杂，应等工具稳定后迁移。
5. `DatasetGenerator`：涉及自建并发上限，迁移前需确认性能和限流语义。

### 17.2 迁移规则

- 先加测试，再替换实现。
- 每次只迁移一个服务或一个方法。
- 不改变业务超时和异常对外表现，除非单独评审。
- 优先保留原有日志字段和 traceId。
- 不把长期后台任务强行放进请求 scope。

## 18. 风险和缓解

| 风险 | 说明 | 缓解 |
|------|------|------|
| 任务不响应中断 | `Future.cancel(true)` 只能发出中断 | 任务内部 IO、循环、sleep 必须检查中断或使用可中断 API |
| 异常语义变化 | `CompletableFuture` 常包裹 `CompletionException` | 迁移时保持外层服务异常契约，并补回归测试 |
| 上下文泄漏 | 线程复用时 ThreadLocal 未清理会污染后续任务 | `ContextRestorer.close()` 必须恢复 previous |
| 并发过高 | 虚拟线程降低线程成本，但不降低下游资源压力 | 对模型调用、数据库、外部 API 配置 `maxConcurrency` |
| 流式生命周期混淆 | Reactor subscription 生命周期不同于方法调用 | 第一阶段不包住完整 `Flux` |
| 过度抽象 | 一次性设计太多策略和上下文类型 | 第一阶段只实现最小闭环 |

## 19. 结论

基于 JDK 21 稳定 API 实现项目内结构化并发工具是可行的。核心不是复制 JDK 预览 API 的类名，而是把结构化并发的关键语义落到项目基础设施：

- `TaskScope` 管生命周期。
- `Subtask` 管子任务结果。
- `ScopePolicyHandler` 管成功/失败策略。
- `ContextAwareCallable` 管 ThreadLocal 类上下文传递。
- `ScopeExecutorFactory` 管虚拟线程和线程池选择。

第一阶段应小步实现，并优先在请求内并发、失败语义清晰的场景试点。等工具的取消、超时、异常聚合和上下文传递都被测试锁住后，再逐步替换散落的 `CompletableFuture` 编排代码。
