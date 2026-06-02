# Quality Guidelines

> Code standards, forbidden patterns, and review criteria.

---

## Code Style

- **Java 21**：使用 record 定义 DTO，使用 var 局部变量类型推断
- **Spring Boot 3.5**：Jakarta EE 命名空间（`jakarta.*`）
- **编码**：UTF-8，LF 换行
- **缩进**：4 空格

---

## Design Principles — 面向对象设计七大原则

### SOLID（五大原则）

| 原则 | 缩写 | 定义 | 项目实践 |
|------|------|------|----------|
| **单一职责** | SRP | 一个类只有一个引起它变化的原因 | 每个类只做一件事；`OrphanChunkCleaner` 只管分片清理，`TeamBucketCleaner` 只管团队桶生命周期 |
| **开闭原则** | OCP | 对扩展开放，对修改关闭 | 新功能 = 新增类，不是改旧类；`BucketResolver` 新增场景只需扩展 `resolve()`，上传策略零改动 |
| **里氏替换** | LSP | 子类必须能替换其基类而不破坏正确性 | 接口实现必须遵守契约；`PersonalUploadStrategy` 和 `TeamUploadStrategy` 都实现 `UploadStrategy`，调用方无感知 |
| **接口隔离** | ISP | 客户端不应被迫依赖它不使用的接口 | 接口按职责拆分，不造大而全的接口；`TeamStatusService` 只暴露 `isTeamActive` / `isTeamMember`，不暴露团队 CRUD |
| **依赖倒置** | DIP | 高层模块不依赖低层模块，两者都依赖抽象 | `ChatService` 依赖 `ChatClientFactory` 接口而非具体实现；`ChunkUploadServiceImpl` 依赖 `TeamStatusService` 接口而非 `TeamMapper` |

### 补充原则

| 原则 | 缩写 | 定义 | 项目实践 |
|------|------|------|----------|
| **迪米特法则** | LoD | 一个对象应该对其他对象保持最少的了解 | Service 不直接操作其他模块的 Mapper；`rag` 模块需要团队状态时通过 `TeamStatusService` 接口查询，不直接注入 `TeamMapper` |
| **合成复用** | CRP | 优先使用对象组合而非继承来复用功能 | 用组合+委托替代继承；`TeamUploadStrategy` 组合 `DocumentValidator`、`FileStorageService`、`BucketResolver`，而非继承某个基类 |

### 强制规则

| 规则 | 说明 |
|------|------|
| **设计模式优先** | 策略、工厂、模板方法等主动运用，不能硬编码 |
| **OCP 强制** | 新功能 = 新增类，不是改旧类；加 Provider 不改 ChatService |
| **封装彻底** | 厂商差异、技术细节不泄漏到上层 |
| **DTO 隔离** | Entity 不暴露给前端，通过 DTO 转换 |
| **编程式事务** | `TransactionTemplate`，不用 `@Transactional` |
| **新增功能 Checklist** | PRD 必须验证"新增同类功能的步骤"确保零修改现有文件 |
| **批判式思考** | 每次编码前审视：设计模式、SOLID、OOP、可读性、可维护性 |

---

## Security Checklist

每次改动涉及以下内容时必须检查：

- [ ] **密码**：BCrypt 哈希，不存明文
- [ ] **Token**：HttpOnly Cookie 存储，不在 JSON body 返回
- [ ] **权限**：`@PreAuthorize` 注解保护接口
- [ ] **输入校验**：`@Valid` + Jakarta Validation
- [ ] **状态枚举**：用枚举类约束，不接受裸 Integer
- [ ] **唯一约束**：业务层先查重 + 数据库 partial unique index 兜底
- [ ] **软删除**：查询条件必须包含 `deleted = 0`
- [ ] **错误消息**：认证失败不暴露具体原因

---

## Forbidden Patterns

| 模式 | 替代方案 | 原因 |
|------|---------|------|
| `@Transactional` | `TransactionTemplate` | 精确控制事务边界 |
| JPA / Hibernate | MyBatis-Plus | 项目已全量替换 |
| `IllegalArgumentException` | `BusinessException` | 统一异常处理 |
| `System.out.println` | SLF4J Logger | 日志框架 |
| 裸 Integer 状态字段 | 枚举类 + 校验 | 防止无效值 |
| Controller 内 try-catch | GlobalExceptionHandler | 统一错误格式 |
| 返回 Entity 给前端 | DTO 转换 | 隔离内部结构 |
| Token 放 JSON body | HttpOnly Cookie | 安全性 |
| Flyway | ~~已移除~~ → **已重新引入** | V4+ 迁移通过 Flyway 管理 |
| `docker pull *:alpine` | `*:bookworm` | 项目规则 |
| 不经允许拉 Docker 镜像 | 先问用户 | 项目规则 |

---

## DTO Rules

- Request DTO：用 `record`，加 `@Valid` 注解
- Response DTO：用 `record`，不加敏感信息（如 permissions 列表）
- 字段校验：`@NotBlank`、`@Email`、`@Size`、`@Pattern`
- email 统一 `toLowerCase` 处理

---

## Scenario: Structured Request-Scoped Concurrency

### 1. Scope / Trigger

- Trigger: 新增或迁移请求内并发编排时，使用 `com.smart.rag.common.concurrent`，不要直接把多个 `CompletableFuture` 暴露给业务层。
- Phase 1 范围：只允许 `SHUTDOWN_ON_FAILURE`、`COLLECT_ALL`、虚拟线程 executor、MDC 传递；不要顺手加入 SecurityContext、RequestContext、Micrometer、Reactor 生命周期绑定或成功即取消策略。
- Phase 3 范围：已支持 `ScopedTaskProperties`、`PLATFORM_THREAD_POOL`、`SHARED_EXECUTOR`、SecurityContext / RequestContext carrier 和 `ScopeObserver`。不要把这些能力扩展成 Phase 4 策略，也不要新增依赖只为接 Micrometer；如需指标，用 `ScopeObserver` 适配已有指标系统。
- Phase 4 范围：已支持 `SHUTDOWN_ON_SUCCESS`、`PARTIAL_SUCCESS_OR_THROW`、`QUORUM_SUCCESS`、`ScopeJoiner<R>`、`ScopedFlux` 和嵌套 scope 结构违规检测；新增使用方必须显式选择策略并补对应测试。

### 2. Signatures

```java
ScopedTasks.open(String name)
ScopedTasks.open(String name, ScopePolicy policy)
ScopedTasks.open(String name, ScopeOptions options)
ScopedTasks.open(String name, ScopeOptions options, ExecutorService executor)

TaskScope.fork(String name, Callable<T> task)
TaskScope.fork(String name, Runnable task)
TaskScope.join()
TaskScope.joinUntil(Duration timeout)
TaskScope.join(ScopeJoiner<R> joiner)
TaskScope.throwIfFailed()
TaskScope.subtasks()

Subtask.result()
Subtask.exception()
Subtask.cancel()

ScopedTaskProperties.toOptions(String name)
ScopeObserver.onScopeClosed(ScopeReport report)
ScopeJoiner.successfulResults(Class<T> resultType)
ScopedFlux.using(Supplier<TaskScope>, Function<TaskScope, Publisher<T>>, Consumer<TaskScope>)
```

### 3. Contracts

- `TaskScope` 必须用 `try-with-resources` 管理生命周期。
- `fork` / `join` / `joinUntil` / `throwIfFailed` / `close` 只能由创建 scope 的 owner 线程调用。
- `join` 或 `joinUntil` 只能成功调用一次；join 后禁止继续 `fork`。
- `Subtask.result()` 是非阻塞方法，必须在等待后读取；未完成、失败、取消分别抛 `SubtaskNotCompletedException`、`SubtaskFailedException`、`SubtaskCancelledException`。
- `close()` 必须取消未完成任务，并在 `ScopeOptions.closeTimeout` 内等待任务终止。
- `COLLECT_ALL` 不代表忽略失败；调用方必须调用 `throwIfFailed()` 或读取失败任务的 `exception()`，否则关闭时记录 WARN。
- `SHUTDOWN_ON_SUCCESS` 表示竞速成功：任一任务成功后取消未完成任务；全部失败时 `throwIfFailed()` 聚合全部失败。
- `PARTIAL_SUCCESS_OR_THROW` 表示至少一个成功即可继续：等待全部任务结束，至少一个成功时失败分支由调用方降级处理，全部失败时抛 `ScopeExecutionException`。
- `QUORUM_SUCCESS` 必须配置 `ScopeOptions.quorumSuccessCount > 0`；成功数达到阈值后取消未完成任务，未达到阈值时失败仍会聚合抛出。
- `TaskScope.join(ScopeJoiner<R>)` 先执行普通 `join()`，再从 subtask snapshot 收集强类型结果；Joiner 不应阻塞或改变 scope 生命周期。
- `ScopedFlux.using(...)` 只负责把 Reactor subscription 的完成、失败或取消绑定到 scope close；不要用它绕开 Reactor 背压、`doFinally`、usage 记录或 SSE 长连接生命周期。
- 父 scope 活跃时，手动新建线程不能打开脱离父任务树的 scope；scope 管理的子任务内部可以打开内层 scope。
- 允许 partial-success 的业务链路（例如 `HybridSearchService` 的 vector + BM25 检索）必须使用 `COLLECT_ALL` 并在 `join()` 后显式检查每个 `Subtask.exception()`；单分支失败按业务默认值降级，全部分支失败再抛业务异常。
- Provider 刷新这类“单分支失败不影响其它分支”的链路可以在子任务内部捕获异常并返回结果载体；此时 `COLLECT_ALL` 负责统一生命周期、MDC 和观测，业务层继续按结果载体判断成功/失败。
- `inheritSecurityContext` / `inheritRequestContext` 默认关闭；只有任务确实需要读取 Spring Security 或 CAG 请求上下文时才开启。
- `SHARED_EXECUTOR` 必须搭配 `executorOwnedByScope=false`；scope 只能取消本 scope 的任务，不能关闭共享 executor。
- 外部 executor overload 仅用于 Spring 托管或调用方托管的 executor，例如 `ThreadPoolTaskExecutor.getThreadPoolExecutor()`；调用方必须用 `SHARED_EXECUTOR` + `executorOwnedByScope=false`，并继续由原 owner 管理 executor 生命周期。
- 不要在同一个可能饱和的 executor 工作线程中打开 scope 后再 fork 到同一个 executor 并 `join()`；单线程池会自等待死锁。需要保留 IO/CPU 池隔离时，让 owner 线程来自调用栈或另一个 executor，再把子任务 fork 到目标 executor。
- `ScopeExecutorFactory.close()` 必须关闭共享 executor，并使用 `closeTimeout.toNanos()` + `TimeUnit.NANOSECONDS` 等待；不要用 `Duration.toSeconds()`，否则亚秒超时会被截断成 0。
- `ScopeObserver` 只能消费 `ScopeReport` 做日志/指标，不应改变业务控制流或抛出业务异常。
- Provider refresh 只能捕获普通 Provider API 失败；`Error` / fatal JVM 级故障必须重新抛出，不能作为单 Provider 失败结果吞掉。
- `ScopedTaskProperties.quorumSuccessCount` 必须在 setter 阶段拒绝负数；`QUORUM_SUCCESS` 的 builder 配置必须拒绝 0。
- `ScopedTaskProperties.PoolConfig` 必须在 setter 阶段拒绝非法配置：`corePoolSize < 0`、`maxPoolSize <= 0`、`maxPoolSize < corePoolSize`、`queueCapacity <= 0`、`keepAliveSeconds < 0`、`threadNamePrefix` 为空，以及平台/共享 executor 配置对象为 `null`。

### 4. Validation & Error Matrix

| Condition | Error / Behavior |
|-----------|------------------|
| 非 owner 线程调用生命周期方法 | `ScopeViolationException` |
| 已关闭或已 join 后继续 fork | `ScopeClosedException` |
| `SHUTDOWN_ON_FAILURE` 任一任务失败 | 取消未完成任务，`throwIfFailed()` 抛 `ScopeExecutionException` |
| `SHUTDOWN_ON_SUCCESS` 任一任务成功 | 取消未完成任务，`throwIfFailed()` 不抛失败任务异常 |
| `SHUTDOWN_ON_SUCCESS` 全部任务失败 | `throwIfFailed()` 抛 `ScopeExecutionException` 并包含全部失败 |
| `PARTIAL_SUCCESS_OR_THROW` 至少一个任务成功 | 保留成功结果，失败分支由调用方显式降级处理 |
| `PARTIAL_SUCCESS_OR_THROW` 全部任务失败 | `throwIfFailed()` 抛 `ScopeExecutionException` 并包含全部失败 |
| `QUORUM_SUCCESS` 达到 `quorumSuccessCount` | 取消未完成任务，`throwIfFailed()` 不抛失败任务异常 |
| `QUORUM_SUCCESS` 未达到 `quorumSuccessCount` | `throwIfFailed()` 抛已知失败 |
| `joinUntil` 超时且策略为 `SHUTDOWN_ON_FAILURE` | 取消未完成任务并抛 `ScopeTimeoutException` |
| `joinUntil` 超时且策略为 `COLLECT_ALL` | 取消未完成任务，保留已完成结果和已知失败 |
| `joinUntil` 超时且成功类策略已满足成功条件 | 取消未完成任务，允许读取已成功结果 |
| `joinUntil` 超时且成功类策略未满足成功条件 | 抛 `ScopeTimeoutException` |
| 多个任务失败 | `ScopeExecutionException.allFailures()` 返回完整列表，除首个外进入 suppressed |
| partial-success 链路单分支失败 | 该分支降级为业务默认值，继续使用成功分支 |
| partial-success 链路全部分支失败 | 抛业务异常，例如 `BusinessException("向量检索和 BM25 检索均不可用")` |
| provider refresh 单 Provider 拉取失败 | 该 Provider 返回失败结果并跳过注册，其它 Provider 继续拉取和注册 |
| provider refresh Provider 抛出 `Error` | 重新抛出 fatal error，不替换 registry |
| provider refresh 全部 Provider 拉取失败 | 返回 `false`，不替换已有 `ChatClientRegistry` |
| `SHARED_EXECUTOR` 且 `executorOwnedByScope=true` | `ScopeViolationException` |
| `PoolConfig` 非法线程池参数 | setter 阶段抛 `IllegalArgumentException`，不要延迟到 executor 构造时失败 |
| Security/Request context 未显式继承 | 子任务中读取到空上下文 |

### 5. Good/Base/Bad Cases

- Good: 两个必要 IO 子任务并行，用默认 `SHUTDOWN_ON_FAILURE`，等待后 `throwIfFailed()`，再读取结果。
- Base: 允许部分降级的检索/批量任务用 `COLLECT_ALL`，等待后逐个检查 `result()` / `exception()`。
- Base: Provider refresh 用 `COLLECT_ALL`，每个 provider 子任务内部捕获 provider API 异常并返回 `ProviderResult`，避免单厂商失败取消其它厂商。
- Base: ETL 这类已有 Spring 托管 IO/CPU 池的批量阶段，用外部 executor overload 保留原线程池隔离，同时把 fork/join、timeout 和观测收敛到 `TaskScope`。
- Base: 多 provider 竞速或镜像源竞速可用 `SHUTDOWN_ON_SUCCESS`；必须确认失败分支取消不会破坏外部资源状态。
- Base: 至少 N 个独立候选成功即可继续时用 `QUORUM_SUCCESS`，并显式配置 `quorumSuccessCount`。
- Base: 流式响应只把准备阶段或订阅边界绑定到 `ScopedFlux`，完整 SSE 仍由 Reactor 生命周期管理。
- Base: 需要跨线程读取认证用户或 CAG 上下文时，显式开启 `inheritSecurityContext` / `inheritRequestContext`，并用测试证明子任务修改不会污染 owner 线程。
- Bad: 把 `TaskScope` 传给其它线程继续 `fork`，或对允许部分成功的链路直接使用 fail-fast 策略。
- Bad: 在业务服务里新建静态 `Executors.newVirtualThreadPerTaskExecutor()` 并手写 MDC 恢复，绕开 `ScopedTasks` 的生命周期和观测。
- Bad: 使用共享 executor 时让 scope 关闭 executor，导致其它请求的并发任务被误杀。
- Bad: 在 `cpuExecutor` 的 worker 内打开使用同一个 `cpuExecutor` 的 scope 并同步 `join()`；如果池大小为 1 或已满，会永久等待自己排队的子任务。

### 6. Tests Required

- owner 约束、join 后禁 fork、close 幂等。
- success result、failed/cancelled/not-completed result exception。
- `SHUTDOWN_ON_FAILURE` 取消未完成任务。
- `COLLECT_ALL` 等待剩余任务并聚合失败。
- partial-success 业务迁移必须覆盖：全部成功、左分支失败右分支成功、左分支成功右分支失败、全部失败。
- `joinUntil` 和 `defaultTimeout` 超时语义。
- MDC 子任务可见且调用方线程不被污染。
- parent timeout 能通过中断传播到 nested scope。
- 第二个真实使用方迁移时，测试必须覆盖原业务容错语义、MDC 继承、调用方线程上下文不污染，以及已有 registry / 缓存不被失败刷新覆盖。
- Phase 3 基础设施必须覆盖：配置默认值应用、平台/共享 executor 生命周期、Security/Request context 继承和恢复、`ScopeObserver` 收到汇总。
- Phase 3 配置回归必须覆盖：非法 `PoolConfig` 早失败、共享 executor 关闭会等待亚秒 `closeTimeout`、外部 executor overload 不关闭调用方 executor、Provider fatal `Error` 不会被 `COLLECT_ALL` 结果容器吞掉。
- Phase 4 策略必须覆盖：`SHUTDOWN_ON_SUCCESS` 成功取消与全部失败聚合、`PARTIAL_SUCCESS_OR_THROW` 单成功通过与全部失败抛错、`QUORUM_SUCCESS` 达阈值取消、`ScopeJoiner` 强类型结果收集、`ScopedFlux` subscription cancel 关闭 scope、手动子线程 detached scope 被拒绝。

### 7. Wrong vs Correct

#### Wrong

```java
CompletableFuture<A> a = CompletableFuture.supplyAsync(this::loadA);
CompletableFuture<B> b = CompletableFuture.supplyAsync(this::loadB);
return combine(a.join(), b.join());
```

#### Correct

```java
try (TaskScope scope = scopedTasks.open("load-and-combine")) {
    Subtask<A> a = scope.fork("load-a", this::loadA);
    Subtask<B> b = scope.fork("load-b", this::loadB);

    scope.joinUntil(Duration.ofSeconds(3));
    scope.throwIfFailed();

    return combine(a.result(), b.result());
}
```

#### Wrong

```java
CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
    try (TaskScope scope = scopedTasks.open("cpu", sharedCpuOptions, cpuExecutor)) {
        scope.fork("cpu-work", this::cpuWork);
        scope.join();
    }
}, cpuExecutor);
```

#### Correct

```java
try (TaskScope scope = scopedTasks.open("cpu", sharedCpuOptions, cpuExecutor)) {
    scope.fork("cpu-work", this::cpuWork);
    scope.join();
}
```

---

## Git Commit Rules

- 每次改动后必须 commit + push
- commit message 首行必须使用 Conventional Commits 风格：`type(scope): summary`
- `type` 用一个词说明改动类型，例如 `fix`、`feat`、`docs`、`test`、`refactor`、`chore`
- `scope` 必须写清楚涉及模块，多个模块用逗号分隔，例如 `fix(rag,chat): fix stale unit tests`
- `summary` 用简短英文说明本次提交做了什么；正文和 git trailers 可继续按 Lore Commit Protocol 补充约束、取舍和验证信息
