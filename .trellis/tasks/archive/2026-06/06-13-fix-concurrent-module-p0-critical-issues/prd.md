# fix-concurrent-module-p0-critical-issues

## Goal

修复 `infrastructure/concurrent/` 模块 code review 发现的 10 个 P0 严重问题。该模块是结构化并发的核心实现，被 LLM client、ETL、HybridSearchService 等关键链路依赖。当前实现存在状态机错乱、资源泄漏、死锁默认值、误关外部 executor 等线上事故级别缺陷。

## What I already know

### 已确认的范围决策（来自前置 AskUserQuestion）

* **范围**：只修 P0（10 个），P1/P2 拆下一轮 task
* **ScopedFlux**：保留但加 `@Deprecated` + javadoc 说明"仅支持同步 Publisher"限制（不删除）
* **spec 同步**：在 `quality-guidelines.md` Forbidden Patterns 增加 virtual thread 豁免条款
* **测试**：每个 P0 修复配套回归测试

### 10 个 P0 清单

| 编号 | 问题 | 文件:行 |
|------|------|---------|
| P0-1 | `Executors.defaultThreadFactory()` 违反 spec（virtual thread 部分 spec 豁免） | `executor/DefaultScopeExecutorFactory.java:31,70` |
| P0-2 | `DefaultTaskScope` 428 行 God Class（>300 行上限），11 项职责 | `DefaultTaskScope.java` |
| P0-3 | `fork()` 中 `state.add()` 早于 `executor.submit()`，submit 失败留下悬挂 subtask | `DefaultTaskScope.java:93-95` |
| P0-4 | `DefaultSubtask.result()` 绕过 `exception()` 方法，`failureObserved` 标志失效 | `DefaultSubtask.java:42-49` |
| P0-5 | `markFailed` 在 state=CANCELLED 时静默吞异常 | `DefaultSubtask.java:100-109` |
| P0-6 | `defaultTimeout=ZERO` 默认值死锁风险（ZERO 实为"无限等待"） | `ScopeOptions.java:66` + `DefaultTaskScope.java:100-108` |
| P0-7 | SHARED_EXECUTOR + 外部 executor 缺 cross-field 校验，误关外部资源 | `ScopeOptions.java:69` + `DefaultTaskScope.java:158-160` |
| P0-8 | `Cleaner` 注册的 `ScopeCleanup` 只 log 不清理（线程/executor 泄漏） | `DefaultTaskScope.java:33,75,408-427` |
| P0-9 | `ScopeNestingGuard.scopeClosed()` 无条件 pop 栈顶，违反 LIFO | `ScopeNestingGuard.java:65-77` |
| P0-10 | ScopedFlux 与 owner-thread 模型冲突（本轮加 @Deprecated，不删） | `ScopedFlux.java:15-32` |

## Requirements

* 修复全部 10 个 P0 问题
* 不改变 `TaskScope`、`Subtask`、`ScopedTasks`、`ScopeOptions` 公开 API 签名
* spec 同步更新（virtual thread 豁免条款 + Cleaner 必须真正清理检查项）
* 每个 P0 至少 1 个回归测试，覆盖关键场景
* 所有现有测试保持绿色

## Acceptance Criteria

* [ ] `mvn compile` 通过
* [ ] `mvn test -pl . -Dtest="com.smart.rag.common.concurrent.*"` 全绿（旧 + 新测试）
* [ ] `DefaultTaskScope.java` 行数 ≤ 300（God Class 拆分生效）
* [ ] `quality-guidelines.md` Forbidden Patterns 表格包含 virtual thread 豁免
* [ ] 10 个 P0 各有对应回归测试用例（具体清单见 Technical Approach）
* [ ] GitNexus `detect_changes()` 验证只影响预期 symbols/flows
* [ ] `application.yml` 增加 `app.scoped-tasks.default-timeout: 30s` 默认配置（覆盖 `ScopedTaskProperties` 默认）

## Definition of Done

* 单元测试新增覆盖率验证（每个 P0 至少 1 个用例）
* `mvn -q -DskipTests=false test` 通过
* spec 文档（`quality-guidelines.md`、`code-review-checklist.md`）同步更新
* PR 描述包含 10 个 P0 的 before/after 对照
* 提交按 Conventional Commits：`fix(concurrent): ...`
* 8 个调用方文件人工回归验证清单（不强制改代码，但要确认行为兼容）

## Out of Scope (explicit)

* P1 问题（17 个）—— 拆下一轮 task
* P2 问题（10 个）—— 拆下下轮 task
* ScopedFlux 删除（本轮仅 @Deprecated）
* owner-thread 模型重设计（与 reactive 解耦的设计推迟）
* PartialSuccessOrThrowPolicy 提前停止行为调整（P1 范围）
* `ScopeExecutionException.allFailures()` 命名重命名（P1 范围）
* `TaskScope.join(ScopeJoiner)` 默认方法加 throwIfFailed（P1 范围，避免破坏调用方）
* InheritableThreadLocal 移除（P1-10 范围）

## Technical Approach

### P0-1 spec 豁免 + ThreadFactory 修复

1. `quality-guidelines.md` Forbidden Patterns `Executors.newXxx()` 行下增加豁免条款：
   - 例外：`Executors.newVirtualThreadPerTaskExecutor()` 和 `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())` 允许——virtual thread per task 不构成池化
2. `DefaultScopeExecutorFactory.java:70` `Executors.defaultThreadFactory()` → `Thread.ofPlatform().factory()`
3. `DefaultScopeExecutorFactory.java:31` 保留 `Executors.newVirtualThreadPerTaskExecutor()`（符合豁免）
4. **回归测试**：单测验证 `executorMode=PLATFORM_THREAD_POOL` 时 worker 线程名前缀正确

### P0-2 God Class 拆分（5 个协作类）

| 新类 | 职责 | 吸收的方法 |
|------|------|-----------|
| `ScopeLifecycle` | owner 校验、closed/joined 状态机、Cleaner 集成 | `ensureOwner`、`closed`、`joined`、`failuresHandled`、P0-8 Cleaner 重写 |
| `ScopeJoinEngine` | join 主循环、completion signaling、drainSignals | `joinInternal`、`activeCompletionSignals`、`drainCompletedSignalsOnOwnerThread`、P0-3 fork 顺序修复 |
| `ScopeTimeoutHandler` | timeout 决策、policy 失败分类 | `onTimeout`、`shouldTimeoutThrow`、`unacceptableFailures` |
| `ScopeExecutorLifecycle` | executor shutdown、subtask 等待终止 | `shutdownOwnedExecutor`、`waitForTermination`、`waitForTerminationRemaining`、`cancelUnfinished` |
| `ScopeReporter` | 报告生成、observer 通知、日志 | `scopeReport`、`notifyScopeObserver`、`logScopeSummary`、`warnAboutUnhandledCollectAllFailures` |

`DefaultTaskScope` 作为 facade，委托上述 5 类。公开 API（`fork/join/joinUntil/throwIfFailed/subtasks/close`）签名不变。

5 个协作类为包级可见（`final class`，包内 `DefaultTaskScope` 直接组合），不暴露给 `infrastructure.concurrent` 包外。

### P0-3 fork 顺序修复

`ScopeJoinEngine.fork()`（原 `DefaultTaskScope.fork`）改顺序：
```java
// Before
state.add(subtask);
Future<T> future = executor.submit(...);  // may throw REE
subtask.attachFuture(future);

// After
Future<T> future;
try {
    future = executor.submit(...);
} catch (RejectedExecutionException ex) {
    subtask.markCancelled(Duration.ZERO);
    subtask.markTerminated();
    state.add(subtask);  // 仍加入 state 以便 close 时统计
    throw new ScopeExecutionException(options.name(), List.of(ex));
}
subtask.attachFuture(future);
state.add(subtask);
```
**回归测试**：mock executor 抛 REE，验证 subtask 状态、scope 后续 close 不卡。

### P0-4 result() 走 exception() 方法

`DefaultSubtask.result()` FAILED 分支：
```java
case FAILED -> throw new SubtaskFailedException(name, exception());  // 调用方法而非字段
```
**回归测试**：FAILED 状态调 result() 后，`failureObserved()` 返回 true。

### P0-5 CANCELLED 状态保留异常

`DefaultSubtask.markFailed` CANCELLED 分支：
```java
} else if (state.get() == TaskState.CANCELLED) {
    exception.set(error);   // 保留 teardown error
    completionSignal.complete(this);
}
```
state 仍为 CANCELLED（保持取消优先），但 `subtask.exception()` 能查到 teardown error。
**回归测试**：worker teardown 抛错时，subtask.exception() 返回该错且 failureObserved=true。

### P0-6 defaultTimeout=30s + ZERO 拦截

1. `ScopeOptions.java` 构造器：`if (defaultTimeout.isZero() || defaultTimeout.isNegative())` 抛异常
2. `ScopeOptions.java` 增加常量 `public static final Duration NO_TIMEOUT = Duration.ofMillis(Long.MAX_VALUE);`（语义清晰）
3. `ScopeOptions.Builder.defaultTimeout` 默认 `Duration.ofSeconds(30)`
4. `ScopedTaskProperties.defaultTimeout` 默认 `Duration.ofSeconds(30)`
5. `DefaultTaskScope.join()`：`defaultTimeout.isZero()` 走 NO_TIMEOUT 的分支删除；用 `defaultTimeout.equals(NO_TIMEOUT)` 判定无限等待
6. `application.yml`：`app.scoped-tasks.default-timeout: 30s`（兜底配置项）
7. **回归测试**：默认配置 join 在 30s 内超时；显式 NO_TIMEOUT 时不超时；ZERO 抛 ScopeViolationException

### P0-7 cross-field 校验

1. `ScopeOptions` 构造器增加：
```java
if (executorMode == ExecutorMode.SHARED_EXECUTOR && executorOwnedByScope) {
    throw new ScopeViolationException("SHARED_EXECUTOR requires executorOwnedByScope=false");
}
```
2. `DefaultScopedTasks.open(name, options, executor)`：强制 `!options.executorOwnedByScope()`，否则抛异常
3. **回归测试**：构造 SHARED_EXECUTOR + executorOwnedByScope=true 抛异常；外部 executor 不被 scope 关闭

### P0-8 Cleaner 重写

`ScopeLifecycle` 内嵌静态 `ScopeCleanupState`：
```java
private static final class ScopeCleanupState implements Runnable {
    private final ExecutorService executor;
    private final ScopeState state;
    private final boolean executorOwnedByScope;
    private final AtomicBoolean closed;
    private final String scopeName;
    
    public void run() {
        if (closed.get()) return;
        log.warn("TaskScope '{}' leaked (never closed). Cleaning up...", scopeName);
        // cancel all running subtasks
        state.internalSubtasks().stream()
            .filter(t -> !t.isTerminal())
            .forEach(DefaultSubtask::cancel);
        // shutdown only if owned
        if (executorOwnedByScope) {
            executor.shutdownNow();
        }
    }
}
```
Cleaner.register 时传 `ScopeCleanupState`（不引用 DefaultTaskScope，避免阻止 GC）。

SHARED_EXECUTOR 场景下：scope 泄漏时只 cancel subtask（中断 worker 上的 task），不 shutdown 共享 executor。
**回归测试**：模拟 scope 未 close 即丢弃，验证 owned executor 被 shutdownNow；SHARED_EXECUTOR 场景下共享 executor 存活。

### P0-9 scopeClosed 按 scopeId 移除

`ScopeNestingGuard`：
```java
static long currentScopeId() { return LOCAL_SCOPE_IDS.get().peek(); }

static void scopeOpened() {
    long scopeId = NEXT_SCOPE_ID.incrementAndGet();
    LOCAL_SCOPE_IDS.get().push(scopeId);
    ACTIVE_SCOPE_IDS.add(scopeId);
    LOCAL_SCOPE_DEPTH.set(LOCAL_SCOPE_DEPTH.get() + 1);
}

static void scopeClosed(long expectedScopeId) {
    int depth = LOCAL_SCOPE_DEPTH.get() - 1;
    Deque<Long> localScopeIds = LOCAL_SCOPE_IDS.get();
    if (!localScopeIds.isEmpty()) {
        Long popped = localScopeIds.pop();
        if (popped != expectedScopeId) {
            // LIFO 违反，回滚并抛异常
            localScopeIds.push(popped);
            throw new ScopeViolationException(
                "Scope closed out of order: expected " + expectedScopeId + 
                ", got " + popped);
        }
        ACTIVE_SCOPE_IDS.remove(popped);
    }
    ...
}
```
`DefaultTaskScope` 持有 `scopeId`（构造时从 `ScopeNestingGuard.scopeOpened()` 返回值获取），close 时传入。
**回归测试**：正常 LIFO 关闭；乱序关闭抛 ScopeViolationException。

### P0-10 ScopedFlux 加 @Deprecated

```java
/**
 * @deprecated 此 wrapper 与 TaskScope 的 owner-thread 模型冲突——reactor 异步调度
 * 后调用 fork/join/close 必抛 ScopeViolationException。仅支持同步发射的 Publisher。
 * 项目当前同步链路（Spring MVC + LLM client）不再需要此抽象，请直接使用
 * try-with-resources 模式：try (TaskScope scope = scopedTasks.open(...)) { ... }
 */
@Deprecated(since = "0.x", forRemoval = true)
public static <T> Flux<T> using(...) { ... }
```
**回归测试**：无需新增（行为不变），仅 API 标记。

## Decision (ADR-lite)

**Context**：concurrent 模块是关键基础设施，存在多个线上事故级别缺陷。10 个 P0 问题中既有设计层面（God Class、Cleaner 语义）也有实现层面（race、悬挂 subtask、状态机错乱）。

**Decision**：
1. **范围**：本轮只修 P0，P1/P2 拆后续 task（避免 PR diff 过大、review 风险高）
2. **拆分粒度**：完整拆 5 个协作类（避免下一轮 P1 又要拆，一次到位）
3. **defaultTimeout=30s**：项目惯用值，覆盖大部分场景；LLM 显式覆盖
4. **Cleaner 策略**：owned 才真正清理（shutdownNow），SHARED_EXECUTOR 只警告（保护共享资源）
5. **CANCELLED 异常**：本地保留到 subtask.exception（不污染 scope 级异常语义）
6. **ScopedFlux**：保留 + @Deprecated（保持 API 兼容，给未来移除留窗口）
7. **spec 同步**：virtual thread 豁免条款落地（让代码与规范一致）

**Consequences**：
- ✅ DefaultTaskScope 行数从 428 降到 ~150（facade）
- ✅ 资源泄漏兜底（Cleaner 真清理）
- ✅ 默认死锁风险消除（30s timeout）
- ✅ 外部 executor 不再被误关（cross-field 校验）
- ⚠️ 5 个新类增加包内复杂度（但每个 < 150 行，单测更容易）
- ⚠️ 8 个调用方需人工回归（API 兼容，但 defaultTimeout 默认值变化可能影响行为）
- ⚠️ ScopedFlux @Deprecated 是过渡状态，下下轮需要彻底删除决策

## Expansion Notes

### Future evolution

* owner-thread 与 reactive 解耦的设计（如 scope 绑 reactor Context）—— 推迟到 reactive 真有需求时
* scope 嵌套跨 executor 跟踪 —— 当前 ScopeNestingGuard 已有基础，未来可扩展

### Related scenarios

* 项目其他 Cleaner 使用点 —— 本次只改 DefaultTaskScope，其他不动
* 项目其他 try-with-resources 模式 —— 不在本次范围
* `application.yml` 配置覆盖 —— 需要文档化 `app.scoped-tasks.*` 全部配置项

### Failure & edge cases

* 修复过程中保留所有现有测试绿（1104+427+206=1737 行测试不能挂）
* `defaultTimeout` 默认从 ZERO 改 30s 是行为变化——已有调用方依赖"无限等待"会显式超时（可能有副作用的 test case 需调整）
* 拆分后跨类共享状态（state、options）用构造注入，避免 static
* Cleaner 在 JVM shutdown 时不保证运行——这是 JDK 限制，不修复

## Technical Notes

### 受影响调用方（grep 结果，需在修复后回归验证）

* `evaluation/dataset/DatasetGenerator.java`
* `agent/service/HybridSearchService.java`
* `rag/etl/FastTrackStrategy.java`
* `rag/etl/StandardStrategy.java`
* `infrastructure/llm/client/bailian/BailianEmbeddingClient.java`
* `infrastructure/llm/strategy/provider/BailianEmbeddingClientFactory.java`
* `infrastructure/llm/registry/LlmClientRegistry.java`
* `config/ScopedTaskAutoConfiguration.java`

### 关键约束

* Java 21（virtual thread 稳定可用，但 structured concurrency preview API 不用）
* Spring Boot 3.5
* 不引入新依赖
* spec 中 `infrastructure.concurrent` 异常家族（ScopeExecutionException 等）已是规范的一部分，不可改名

### spec 修改点

* `.trellis/spec/backend/quality-guidelines.md` §Forbidden Patterns `Executors.newXxx()` 行 → 增加 virtual thread 豁免说明
* `.trellis/spec/backend/code-review-checklist.md` §3 资源管理 → 补充 "结构化并发 Cleaner 必须真正清理（不只是 log）" 检查项
* `.trellis/spec/backend/quality-guidelines.md` §Structured Concurrency 核心契约 → 增加 "defaultTimeout 必须 > 0，ZERO 不再表示无限等待，用 ScopeOptions.NO_TIMEOUT 表达"

## Implementation Plan (PR 切分建议)

实施时按依赖顺序推进，每个 PR 独立可合并：

* **PR1**：spec 文档更新（P0-1 豁免 + P0-6 默认值变化说明 + P0-8 检查项）+ ScopeOptions 改造（P0-6 + P0-7 cross-field 校验）+ DefaultSubtask 修复（P0-4 + P0-5）+ ScopeNestingGuard 改造（P0-9）+ ScopedFlux @Deprecated（P0-10）+ DefaultScopeExecutorFactory ThreadFactory 改造（P0-1）—— 小修小补，先稳定基础
* **PR2**：DefaultTaskScope God Class 拆分（P0-2）+ 同步吸收 P0-3 fork 顺序修复 + P0-8 Cleaner 重写到 ScopeLifecycle —— 主体重构，最大 PR
* **PR3**：补充 10 个 P0 的回归测试 + 修复现有测试中因 defaultTimeout 默认值变化导致的失败用例

实际提交可合并为单个 PR（task 内部不分 PR），但 commit 按上述粒度切分便于 review。
